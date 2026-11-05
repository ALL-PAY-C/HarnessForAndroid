package dev.dsh.mobile.core.data.backup

import dev.dsh.mobile.core.download.archive.ZipExtract
import dev.dsh.mobile.core.platform.crypto.PassphraseCipher
import dev.dsh.mobile.core.platform.crypto.WrongPassphraseException
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * 恢复引擎（设计见 research/T913-backup.md §2.3）。
 *
 * 三条铁律：
 *  1. 先解到 staging，校验通过才动活库 —— 任何异常都只丢 staging，活数据保持原样；
 *  2. Replace 必须先成功快照（pre-restore），快照失败即中止，不给「删了没得回」的机会；
 *  3. 包内数据库版本高于本机一律拒绝；低于本机走迁移链升上来，升不动就明确拒绝。
 *
 * 恢复完不重启进程：调用方决定何时 restartRuntime，引擎只负责把数据摆对。
 */
class RestoreEngine(
    private val roots: BackupRoots,
    private val db: BackupDb,
    private val prefs: PrefsSource,
    private val prefsSink: PrefsSink,
    /** Replace 前拍快照；返回 null 表示快照失败，恢复必须中止。 */
    private val snapshotBeforeReplace: () -> File? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    enum class Mode { Merge, Replace }

    data class Report(
        val mode: Mode,
        val sessions: Int,
        val messages: Int,
        val plugins: Int,
        val files: Int,
        val warnings: List<String>,
        val preRestore: File? = null,
    )

    /** 只读 manifest，不动磁盘。 */
    fun preview(file: File): BackupManifest {
        var text: String? = null
        ZipExtract.forEachEntry(
            file.inputStream().buffered(),
            ZipExtract.Options(maxEntries = MAX_ENTRIES, maxTotalBytes = BackupWriter.MAX_PACKAGE_BYTES),
        ) { name, isDirectory, data ->
            if (!isDirectory && name == BackupWriter.MANIFEST_ENTRY && text == null) {
                text = data.readBytes().toString(Charsets.UTF_8)
            }
        }
        val body = text ?: throw IllegalStateException("不是有效的备份包：缺少 manifest.json")
        return BackupManifestCodec.decode(body)
    }

    fun restore(file: File, mode: Mode, passphrase: String = ""): Result<Report> = runCatching {
        val manifest = preview(file)
        val verdict = compareDb(manifest.dbVersion, db.version)
        if (verdict == DbVerdict.Newer) {
            throw IllegalStateException("包内数据库版本高于本机，请先升级 App")
        }

        var preRestore: File? = null
        if (mode == Mode.Replace) {
            preRestore = snapshotBeforeReplace()
                ?: throw IllegalStateException("恢复前快照失败，已中止恢复")
        }

        val staging = File(roots.restoreStagingDir, "r-" + clock()).apply { mkdirs() }
        try {
            ZipExtract.extract(
                file,
                staging,
                ZipExtract.Options(
                    maxEntries = MAX_ENTRIES,
                    maxEntryBytes = BackupWriter.MAX_PACKAGE_BYTES,
                    maxTotalBytes = BackupWriter.MAX_PACKAGE_BYTES,
                ),
            )
            verifySections(staging, manifest)

            val warnings = manifest.warnings.toMutableList()
            val (touched, messageCount) = restoreDatabase(staging, manifest, mode)
            restoreSettings(staging, mode)
            val plugins = restorePlugins(staging, mode)
            val files = restoreTrees(staging, mode)
            restoreSecrets(staging, manifest, passphrase)

            db.clearWarmLayers(touched)
            Report(
                mode = mode,
                sessions = touched.size,
                messages = messageCount,
                plugins = plugins,
                files = files,
                warnings = warnings,
                preRestore = preRestore,
            )
        } catch (e: WrongPassphraseException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(readable(e), e)
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    // ---- 校验 ----

    /**
     * 逐分节重算 sha256：条目按「名字 + 字节数 + 条目哈希」排序后拼接再哈希。
     * 与 [BackupWriter] 的算法必须逐字一致，否则合法包会被判成损坏。
     */
    private fun verifySections(staging: File, manifest: BackupManifest) {
        val files = staging.walkTopDown().filter { it.isFile }.toList()
        for (section in manifest.sections) {
            if (!section.included) continue
            val prefixes = PREFIXES[section.id] ?: continue
            val members = files
                .map { it to it.relativeTo(staging).path.replace(File.separatorChar, '/') }
                .filter { (_, rel) -> prefixes.any { rel.startsWith(it) } }
            val records = members.map { (f, rel) ->
                rel + "\t" + f.length() + "\t" + BackupWriter.sha256(f.readBytes())
            }
            if (records.size != section.entries) {
                throw IllegalStateException("校验失败：" + section.id + " 条目数不符")
            }
            val actual = BackupWriter.sha256(records.sorted().joinToString("\n").toByteArray())
            if (actual != section.sha256) {
                throw IllegalStateException("校验失败：" + section.id + " 内容与 manifest 不符")
            }
        }
    }

    // ---- 数据库 ----

    private fun restoreDatabase(
        staging: File,
        manifest: BackupManifest,
        mode: Mode,
    ): Pair<Set<String>, Int> {
        val dumpFile = File(staging, "db/dsh-mobile.db")
        if (!dumpFile.isFile) {
            if (manifest.sections.any { it.id == BackupSections.DB && it.included }) {
                throw IllegalStateException("校验失败：包内缺少数据库文件")
            }
            return emptySet<String>() to 0
        }
        val dump = db.open(dumpFile)
        try {
            val incomingSessions = dump.rows(TABLE_SESSIONS)
            val incomingMessages = dump.rows(TABLE_MESSAGES)
            val touched = incomingSessions.mapNotNull { it.str("id") }.toSet()
            val liveTitles = db.titles()

            val prepared = incomingSessions.map { row ->
                val copy = LinkedHashMap(row.values)
                // endpointId 指向产包设备的端点，搬过来必然失效：置空，让用户在设置里重选。
                if (copy.containsKey("endpointId")) copy["endpointId"] = ""
                if (mode == Mode.Merge) {
                    val id = row.str("id").orEmpty()
                    val title = row.str("title").orEmpty()
                    if (id !in liveTitles && title.isNotEmpty() &&
                        liveTitles.values.any { it == title }
                    ) {
                        copy["title"] = title + IMPORT_SUFFIX
                    }
                }
                Row(copy)
            }

            return db.transaction {
                if (mode == Mode.Replace) db.clearContent()
                db.upsert(TABLE_SESSIONS, prepared)
                db.upsert(TABLE_MESSAGES, incomingMessages)
                touched to incomingMessages.size
            }
        } finally {
            dump.close()
        }
    }

    // ---- 设置 ----

    private fun restoreSettings(staging: File, mode: Mode) {
        val datastoreDir = File(staging, "datastore")
        if (datastoreDir.isDirectory) {
            roots.datastoreDir.mkdirs()
            for (f in datastoreDir.listFiles().orEmpty()) {
                if (!f.isFile || !f.name.endsWith(".preferences_pb")) continue
                val name = f.name.removeSuffix(".preferences_pb")
                if (name !in DATASTORE_WHITELIST) continue
                val target = File(roots.datastoreDir, f.name)
                val merged = if (mode == Mode.Merge) {
                    val local = runCatching { PreferencesPb.decode(target.readBytes()) }.getOrDefault(LinkedHashMap())
                    val incoming = PreferencesPb.decode(f.readBytes())
                    PreferencesPb.merge(local, incoming, DEVICE_SETTING_KEYS)
                } else {
                    val incoming = PreferencesPb.decode(f.readBytes())
                    // Replace 也保留设备键：路径类设置换成别的手机的，本机反而不能用。
                    val local = runCatching { PreferencesPb.decode(target.readBytes()) }.getOrDefault(LinkedHashMap())
                    val kept = LinkedHashMap<String, PbValue>()
                    for (key in DEVICE_SETTING_KEYS) local[key]?.let { kept[key] = it }
                    PreferencesPb.merge(kept, incoming, DEVICE_SETTING_KEYS)
                }
                target.writeBytes(PreferencesPb.encode(merged))
            }
        }

        val prefsDir = File(staging, "prefs")
        if (prefsDir.isDirectory) {
            for (f in prefsDir.listFiles().orEmpty()) {
                if (!f.isFile || !f.name.endsWith(".json")) continue
                val name = f.name.removeSuffix(".json")
                if (name !in PREFS_WHITELIST) continue
                val values = runCatching { PrefsCodec.decode(f.readText()) }.getOrElse { emptyMap() }
                if (values.isEmpty()) continue
                prefsSink.apply(name, values, mode == Mode.Replace)
            }
        }
    }

    // ---- 插件 ----

    private fun restorePlugins(staging: File, mode: Mode): Int {
        val src = File(staging, "plugins")
        if (!src.isDirectory) return 0
        var count = 0
        for (dir in src.listFiles().orEmpty()) {
            if (!dir.isDirectory) continue
            val target = File(roots.pluginsDir, dir.name)
            val incoming = versionOf(dir)
            val local = versionOf(target)
            if (mode == Mode.Merge && local != null && incoming != null && compareVersion(incoming, local) <= 0) {
                continue
            }
            target.deleteRecursively()
            copyTree(dir, target)
            count++
        }
        return count
    }

    private fun versionOf(pluginDir: File): String? =
        File(pluginDir, "current").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    // ---- 附件 / 工作区 ----

    private fun restoreTrees(staging: File, mode: Mode): Int {
        var count = 0
        for ((rel, target) in listOf(
            "files/attachments" to roots.attachmentsDir,
            "files/workspace" to roots.workspaceDir,
        )) {
            val src = File(staging, rel)
            if (!src.isDirectory) continue
            if (mode == Mode.Replace) target.deleteRecursively()
            target.mkdirs()
            count += copyTree(src, target)
        }
        return count
    }

    // ---- 凭证 ----

    private fun restoreSecrets(staging: File, manifest: BackupManifest, passphrase: String) {
        val blob = File(staging, BackupWriter.SECRETS_ENTRY)
        if (!blob.isFile) return
        val info = manifest.secrets
            ?: throw IllegalStateException("凭证节缺少加密参数，无法恢复")
        require(passphrase.isNotEmpty()) { "该备份包含凭证，请输入导出时设置的口令" }
        val plain = PassphraseCipher.open(
            blob.readBytes(),
            passphrase.toCharArray(),
            Base64.getDecoder().decode(info.saltB64),
            Base64.getDecoder().decode(info.ivB64),
        )
        val files = JSONObject(String(plain, Charsets.UTF_8)).optJSONArray("files") ?: return
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val path = item.optString("path")
            val target = targetOfSecret(path) ?: continue
            target.parentFile?.mkdirs()
            target.writeBytes(Base64.getDecoder().decode(item.optString("b64")))
        }
    }

    private fun targetOfSecret(path: String): File? = when {
        path == BackupWriter.SECRET_ENDPOINT_TOKENS -> File(roots.noBackupDir, "endpoint-tokens.json")
        path == BackupWriter.SECRET_PLUGIN_CREDENTIALS -> File(roots.runtimeDir, "plugin-credentials.json")
        path == BackupWriter.SECRET_MODELS -> File(roots.datastoreDir, "dsh_models.preferences_pb")
        path == BackupWriter.SECRET_MCP -> File(roots.sharedPrefsDir, "dsh.mcp.xml")
        else -> null
    }

    private fun copyTree(from: File, to: File): Int {
        var count = 0
        from.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(from).path
            val target = File(to, rel)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
            count++
        }
        return count
    }

    private fun readable(e: Throwable): String = when (e) {
        is IllegalStateException -> e.message ?: "恢复失败"
        is ZipExtract.ZipExtractException -> e.message ?: "备份包读不出来"
        else -> e.message ?: "恢复失败"
    }

    companion object {
        const val MAX_ENTRIES: Int = 50_000
        const val IMPORT_SUFFIX: String = "（导入）"
        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_MESSAGES = "messages"

        private val PREFIXES: Map<String, List<String>> = mapOf(
            BackupSections.DB to listOf("db/"),
            BackupSections.SETTINGS to listOf("datastore/", "prefs/"),
            BackupSections.PLUGINS to listOf("plugins/"),
            BackupSections.SECRETS to listOf("secrets/"),
            BackupSections.ATTACHMENTS to listOf("files/attachments/"),
            BackupSections.WORKSPACE to listOf("files/workspace/"),
        )

        /** 点分版本比较：1.2.10 > 1.2.9。非数字段按字典序。 */
        fun compareVersion(a: String, b: String): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (i in 0 until maxOf(left.size, right.size)) {
                val x = left.getOrNull(i) ?: "0"
                val y = right.getOrNull(i) ?: "0"
                val nx = x.toIntOrNull()
                val ny = y.toIntOrNull()
                val cmp = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}
