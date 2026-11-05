package dev.dsh.mobile.core.data.backup

import dev.dsh.mobile.core.platform.crypto.PassphraseCipher
import dev.dsh.mobile.core.data.db.DshDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dev.dsh.mobile.core.platform.backup.isLegacyPackage
import dev.dsh.mobile.core.model.backup.BackupManifest
import dev.dsh.mobile.core.model.backup.DeviceInfo
import dev.dsh.mobile.core.model.backup.SectionInfo
import dev.dsh.mobile.core.model.backup.Counts
import dev.dsh.mobile.core.model.backup.SecretsInfo
import dev.dsh.mobile.core.model.backup.BackupSections
import dev.dsh.mobile.core.model.backup.BackupSelection

/**
 * 备份包写入（设计见 research/T913-backup.md §2.1/§2.2）。
 *
 * 顺序有讲究：db / 各分节先写，manifest.json 最后写 —— 包没写完就被杀掉时，
 * 得到的 zip 里没有 manifest，恢复侧一眼判定为无效包，不会读到半截数据。
 *
 * 每个分节记 entries / bytes / sha256；sha256 覆盖「条目名 + 字节数 + 条目哈希」，
 * 恢复时逐条重算，任何一条被改过都报「校验失败」。
 */
class BackupWriter(
    private val roots: BackupRoots,
    private val db: BackupDb,
    private val prefs: PrefsSource,
    private val app: AppInfo = AppInfo(0, "0"),
    private val device: DeviceInfo = DeviceInfo("unknown", 0, "unknown"),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 单包硬上限：没有 zip64 支持，超过就写不出可读的包。 */
    val maxPackageBytes: Long = MAX_PACKAGE_BYTES

    fun write(out: File, selection: BackupSelection): BackupManifest {
        val problems = selection.validate()
        require(selection.canExport) { problems.joinToString("；") }

        val warnings = mutableListOf<String>()
        val sections = LinkedHashMap<String, Section>()
        var written = 0L
        val staging = File(roots.backupStagingDir, "pkg-" + clock()).apply { mkdirs() }

        fun section(id: String): Section = sections.getOrPut(id) { Section(id) }

        fun putFile(zip: ZipOutputStream, name: String, file: File, stored: Boolean, acc: Section) {
            val (size, sha) = if (stored) putStored(zip, name, file) else putDeflated(zip, name, file)
            written += size
            require(written <= maxPackageBytes) { "备份包超过 2 GiB 上限，请减少工作区文件后重试" }
            acc.add(name, size, sha)
        }

        fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray, acc: Section) {
            zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
            zip.write(bytes)
            zip.closeEntry()
            written += bytes.size
            require(written <= maxPackageBytes) { "备份包超过 2 GiB 上限，请减少工作区文件后重试" }
            acc.add(name, bytes.size.toLong(), sha256(bytes))
        }

        try {
            out.parentFile?.mkdirs()
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                // ---- db ----
                val accDb = section(BackupSections.DB)
                val dbCopy = File(staging, DshDatabase.NAME)
                db.snapshotTo(dbCopy)
                selection.sessions?.let { ids ->
                    if (ids.isEmpty()) {
                        warnings += "未勾选任何会话：包内数据库只含表结构"
                    }
                    db.pruneSessions(dbCopy, ids)
                }
                putFile(zip, "db/" + DshDatabase.NAME, dbCopy, stored = false, acc = accDb)

                // ---- settings ----
                if (selection.settings) {
                    val acc = section(BackupSections.SETTINGS)
                    for (name in DATASTORE_WHITELIST) {
                        val f = File(roots.datastoreDir, name + ".preferences_pb")
                        if (f.isFile) putFile(zip, "datastore/" + f.name, f, stored = false, acc = acc)
                    }
                    for (name in PREFS_WHITELIST) {
                        val values = prefs.read(name)
                        if (values.isEmpty()) continue
                        // 插件配置一律以密文进包：升级后还没被读过的旧明文在这里换成密文
                        // （口径同下面的凭证节，见 PluginConfigSeal），恢复侧由 PluginConfigVault 解回来。
                        val safe = PluginConfigSeal.sealPrefs(name, values, roots.noBackupDir)
                        putBytes(zip, "prefs/" + name + ".json", PrefsCodec.encode(safe).toByteArray(), acc)
                    }
                }

                // ---- plugins ----
                var pluginCount = 0
                if (selection.plugins.isNotEmpty()) {
                    val acc = section(BackupSections.PLUGINS)
                    for (id in selection.plugins) {
                        val dir = File(roots.pluginsDir, id)
                        if (!dir.isDirectory) {
                            warnings += "插件 " + id + " 未安装，已跳过"
                            continue
                        }
                        val current = File(dir, "current").takeIf { it.isFile }
                            ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                        dir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                            // apk 是可重新下载的安装体；versions/ 里只留 current 指向的那一版，
                            // 其余是旧版本缓存，带上只会把包撑大。
                            if (rel.endsWith(".apk")) return@forEach
                            if (rel.startsWith("versions/") && current != null && !rel.startsWith("versions/" + current + "/")) return@forEach
                            putFile(zip, "plugins/" + id + "/" + rel, f, stored = false, acc = acc)
                        }
                        pluginCount++
                    }
                }

                // ---- attachments ----
                var attachmentCount = 0
                if (selection.attachments) {
                    val acc = section(BackupSections.ATTACHMENTS)
                    val root = roots.attachmentsDir
                    if (root.isDirectory) {
                        root.walkTopDown().filter { it.isFile }.forEach { f ->
                            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                            putFile(zip, "files/attachments/" + rel, f, stored = isMedia(rel), acc = acc)
                            attachmentCount++
                        }
                    }
                }

                // ---- workspace ----
                if (selection.workspace) {
                    val acc = section(BackupSections.WORKSPACE)
                    val root = roots.workspaceDir
                    if (root.isDirectory) {
                        root.walkTopDown().filter { it.isFile }.forEach { f ->
                            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                            if (rel.split('/').any { it in WORKSPACE_SKIP_DIRS }) return@forEach
                            if (f.length() > MAX_WORKSPACE_FILE_BYTES) {
                                warnings += "工作区文件过大已跳过：" + rel
                                return@forEach
                            }
                            putFile(zip, "files/workspace/" + rel, f, stored = isMedia(rel), acc = acc)
                        }
                    }
                }

                // ---- secrets ----
                var secretsInfo: SecretsInfo? = null
                if (selection.secrets) {
                    val acc = section(BackupSections.SECRETS)
                    val payload = secretPayload()
                    if (payload.length() == 0) {
                        warnings += "没有可导出的凭证"
                    } else {
                        val sealed = PassphraseCipher.seal(
                            payload.toString().toByteArray(),
                            selection.passphrase.toCharArray(),
                        )
                        putBytes(zip, SECRETS_ENTRY, sealed.blob, acc)
                        secretsInfo = SecretsInfo(
                            saltB64 = Base64.getEncoder().encodeToString(sealed.salt),
                            ivB64 = Base64.getEncoder().encodeToString(sealed.iv),
                        )
                    }
                }

                // ---- manifest（最后写）----
                val localCounts = db.counts()
                val manifest = BackupManifest(
                    appVersionCode = app.versionCode,
                    appVersionName = app.versionName,
                    dbVersion = db.version,
                    createdAt = clock(),
                    device = device,
                    sections = BackupSections.ALL.map { sections[it]?.info() ?: SectionInfo(it, included = false) },
                    counts = Counts(
                        sessions = localCounts.sessions,
                        messages = localCounts.messages,
                        plugins = pluginCount,
                        attachments = attachmentCount,
                    ),
                    secrets = secretsInfo,
                    warnings = warnings.toList(),
                )
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { method = ZipEntry.DEFLATED })
                zip.write(BackupManifestCodec.encode(manifest).toByteArray())
                zip.closeEntry()
                return manifest
            }
        } catch (e: Throwable) {
            runCatching { out.delete() }
            throw e
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * 凭证节的明文载荷：路径 → 内容。
     *
     * 用「原样搬文件」而不是字段级合并：凭证文件的格式由各自的 Store 决定
     * （endpoint token 是 JSON 数组、模型 apiKey 在 preferences_pb 里、MCP headers 在 xml 里），
     * 逐格式解析只会在格式变动时悄悄漏字段。
     */
    private fun secretPayload(): JSONObject {
        val sources = listOf(
            SECRET_ENDPOINT_TOKENS to File(roots.noBackupDir, "endpoint-tokens.json"),
            SECRET_PLUGIN_CREDENTIALS to File(roots.runtimeDir, "plugin-credentials.json"),
            SECRET_MODELS to File(roots.datastoreDir, "dsh_models.preferences_pb"),
            SECRET_MCP to File(roots.sharedPrefsDir, "dsh.mcp.xml"),
        )
        val array = JSONArray()
        for ((path, file) in sources) {
            if (!file.isFile) continue
            array.put(
                JSONObject()
                    .put("path", path)
                    .put("b64", Base64.getEncoder().encodeToString(file.readBytes())),
            )
        }
        return JSONObject().put("files", array)
    }

    private fun putDeflated(zip: ZipOutputStream, name: String, file: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED; time = file.lastModified() })
        file.inputStream().use { ins ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                size += n
                zip.write(buf, 0, n)
            }
        }
        zip.closeEntry()
        return size to hex(digest.digest())
    }

    /** STORED 必须在写之前就知道 size / crc，所以先整读一遍算摘要，再写一遍。 */
    private fun putStored(zip: ZipOutputStream, name: String, file: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        file.inputStream().use { ins ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                crc.update(buf, 0, n)
                size += n
            }
        }
        zip.putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                this.size = size
                compressedSize = size
                this.crc = crc.value
                time = file.lastModified()
            },
        )
        file.inputStream().use { it.copyTo(zip, BUFFER) }
        zip.closeEntry()
        return size to hex(digest.digest())
    }

    /** 一个分节的累计统计。 */
    private class Section(val id: String) {
        private val records = ArrayList<String>()
        private var bytes = 0L

        fun add(name: String, size: Long, sha: String) {
            records += name + "\t" + size + "\t" + sha
            bytes += size
        }

        fun info(): SectionInfo = SectionInfo(
            id = id,
            included = records.isNotEmpty(),
            entries = records.size,
            bytes = bytes,
            sha256 = sha256(records.sorted().joinToString("\n").toByteArray()),
        )
    }

    companion object {
        // O8-B④: 常量本体在 core:platform.backup，与 isLegacyPackage 判据共用一份。
        const val MANIFEST_ENTRY: String = dev.dsh.mobile.core.platform.backup.BACKUP_MANIFEST_ENTRY
        const val SECRETS_ENTRY: String = "secrets/secrets.bin"
        const val MAX_PACKAGE_BYTES: Long = 2L * 1024 * 1024 * 1024
        const val MAX_WORKSPACE_FILE_BYTES: Long = 50L * 1024 * 1024
        val WORKSPACE_SKIP_DIRS: Set<String> = setOf("linux", "gateway", "tools-output")
        val MEDIA_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "mp4")
        const val SECRET_ENDPOINT_TOKENS = "noBackupFilesDir/endpoint-tokens.json"
        const val SECRET_PLUGIN_CREDENTIALS = "runtime/plugin-credentials.json"
        const val SECRET_MODELS = "datastore/dsh_models.preferences_pb"
        const val SECRET_MCP = "shared_prefs/dsh.mcp.xml"
        private const val BUFFER = 64 * 1024

        fun isMedia(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS

        fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
