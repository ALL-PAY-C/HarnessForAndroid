package dev.dsh.mobile.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 包注册表：安装器只依赖这一条窄缝。
 *
 * 生产实现挂在 [InstalledPlugins] 上（SharedPreferences 里的一段 JSON），单测用
 * [MemoryPackageRegistry]。把它抽出来是因为事务回滚必须能观察「注册表什么时候被写」——
 * 一旦注册表写成功而文件没到位，下次启动就会去加载一个不存在的目录。
 */
interface PackageRegistry {
    fun ids(): Set<String>
    fun raw(id: String): String?
    fun put(id: String, rawJson: String)
    fun remove(id: String)
}

/** 单测用的内存注册表；行为与 SharedPreferences 版一致（整体覆盖、无事务）。 */
class MemoryPackageRegistry(initial: Map<String, String> = emptyMap()) : PackageRegistry {
    private val map = LinkedHashMap<String, String>(initial)
    override fun ids(): Set<String> = map.keys.toSet()
    override fun raw(id: String): String? = map[id]
    override fun put(id: String, rawJson: String) { map[id] = rawJson }
    override fun remove(id: String) { map.remove(id) }
    fun snapshot(): Map<String, String> = LinkedHashMap(map)
}

/** 安装/卸载/修复的统一结果。失败一律走 ok = false + error，不包装成成功。 */
data class PackageResult(
    val ok: Boolean,
    val id: String = "",
    val version: String = "",
    val path: String = "",
    val active: Boolean = false,
    val log: List<String> = emptyList(),
    val error: String? = null,
    /** 仅内存传递，不进 JSON：安装成功后调用方要拿清单，避免再读一次注册表。 */
    val manifest: PluginManifestFile? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("id", id)
        .put("version", version)
        .put("path", path)
        .put("active", active)
        .put("log", JSONArray(log))
        .put("error", error ?: JSONObject.NULL)
}

/** [PluginPackageService.inspect] 的可读快照。 */
data class PackageInfo(
    val id: String,
    val installed: Boolean,
    val active: Boolean,
    val path: String,
    val version: String,
    val lastError: String?,
    val legacy: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("installed", installed)
        .put("active", active)
        .put("path", path)
        .put("version", version)
        .put("lastError", lastError ?: JSONObject.NULL)
        .put("legacy", legacy)
}

/** 解到 staging 的包：目录 + 清单文本 + （npm 包才有）原始 package.json。 */
internal data class StagedPackage(
    val dir: File,
    val manifestText: String,
    val sourcePackage: JSONObject? = null,
)

/** 目录名安全映射：A-Za-z0-9 开头且只含 A-Za-z0-9._- 的段可以直接用。 */
private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val RESERVED_NAME = Regex("(?i)con|prn|aux|nul|com[1-9]|lpt[1-9]")
private val VERSION_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
private val DRIVE_LETTER = Regex("^[A-Za-z]:")

internal fun isSimpleSegment(raw: String): Boolean =
    raw.isNotBlank() && raw.length <= 128 && SAFE_SEGMENT.matches(raw) && !raw.contains("..") && !raw.endsWith(".")

internal fun shortHash(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { "%02x".format(it) }
}

/**
 * id / 版本 → 磁盘目录名。
 *
 * 安全段原样保留（老安装目录不变，升级后旧插件不会「消失」）；含任何路径敏感字符的 id
 * 会被清洗并追加 id 的哈希后缀 —— 两个不同 id 清洗后撞名时不会互相覆盖。
 */
internal fun safeSegment(raw: String): String {
    if (isSimpleSegment(raw)) return raw
    val cleaned = buildString {
        raw.forEach { c -> append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '-') }
    }.trim('.', '-').ifBlank { "pkg" }.take(64)
    return cleaned + "-" + shortHash(raw)
}

/** 返回错误文本；null = 合法。 */
internal fun validatePluginId(id: String): String? {
    if (id.isBlank()) return "plugin.json 缺少 id"
    if (id.length > 128) return "插件 id 过长（" + id.length + " > 128）"
    if (id.contains("..")) return "插件 id 不得包含 ..：" + id
    if (id.any { it == '/' || it == '\\' || it == ':' }) return "插件 id 不得包含路径分隔符：" + id
    if (id.any { it.isISOControl() }) return "插件 id 含控制字符"
    if (id.trim() != id) return "插件 id 首尾不得有空白：" + id
    if (id.startsWith(".") || id.endsWith(".")) return "插件 id 不得以 . 开头或结尾：" + id
    if (RESERVED_NAME.matches(id.substringBefore('.'))) return "插件 id 与系统保留名冲突：" + id
    return null
}

/** 返回错误文本；null = 合法。 */
internal fun validateVersion(version: String): String? {
    if (version.isBlank()) return "plugin.json 缺少 version"
    if (version.length > 64) return "版本号过长（" + version.length + " > 64）"
    if (version.contains("..")) return "版本号不得包含 ..：" + version
    if (version.any { it == '/' || it == '\\' || it == ':' }) return "版本号不得包含路径分隔符：" + version
    if (version.trim() != version) return "版本号首尾不得有空白：" + version
    if (!VERSION_SHAPE.matches(version)) return "版本号含非法字符：" + version
    return null
}

/**
 * 插件包的事务化安装 / 卸载 / 修复 / 巡检。
 *
 * 磁盘布局（root = filesDir/plugins）：
 *   root/install.log                 追加式安装日志（Agent 可读）
 *   root/.errors/<id>.txt            每个 id 最近一次错误
 *   root/.staging/<tmp>/             解包与校验的暂存区
 *   root/<id>/current                指针：当前活动版本目录名（原子替换）
 *   root/<id>/versions/<version>/    每个版本的完整 payload，旧版本保留
 *
 * 事务边界：提交前不动旧版本。先 staging 校验 → 备份同名版本 → 落新版本目录 → 原子换
 * 指针 → onActivate → 写注册表。任何一步抛异常都恢复旧指针、删掉新目录、把备份放回去，
 * 旧插件保持可加载。注册表最后写，所以「文件没了但注册表说装着」不可能发生。
 *
 * 兼容：老版本是扁平结构 root/<id>/plugin.json，读路径会回退到扁平目录，安装时会把它
 * 迁移进 versions/<旧版本>（旧 payload 不丢）。
 */
class PluginPackageService(
    private val root: File,
    private val registry: PackageRegistry,
    /** 提交前校验钩子（默认空）。抛异常 = 中止安装，磁盘不留痕迹。 */
    private val onVerify: suspend (id: String, stagedDir: File) -> Unit = { _, _ -> },
    /** 指针切换后的激活钩子（默认空）。抛异常 = 回滚到旧版本。 */
    private val onActivate: suspend (id: String, activeDir: File) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val stagingRoot = File(root, ".staging")
    private val errorRoot = File(root, ".errors")
    private val logFile = File(root, "install.log")

    // ------------------------------------------------------------------ 路径

    /** 包目录。已有扁平老目录时优先返回它，保证升级后旧插件仍能被读到。 */
    fun idDir(id: String): File {
        val safe = File(root, safeSegment(id))
        if (safe.isDirectory) return safe
        if (isSimpleSegment(id)) {
            val legacy = File(root, id)
            if (legacy.isDirectory) return legacy
        }
        return safe
    }

    fun versionsDir(id: String): File = File(idDir(id), "versions")

    private fun pointerFile(id: String): File = File(idDir(id), "current")

    private fun readPointer(id: String): String? {
        val file = pointerFile(id)
        if (!file.isFile) return null
        val text = runCatching { file.readText().trim() }.getOrNull().orEmpty()
        return text.takeIf { isSimpleSegment(it) }
    }

    private fun writePointer(id: String, versionDirName: String) {
        val dir = idDir(id)
        dir.mkdirs()
        val tmp = File(dir, "current.tmp")
        tmp.writeText(versionDirName)
        val target = pointerFile(id)
        val moved = runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!moved) {
            target.delete()
            check(tmp.renameTo(target)) { "活动版本指针写入失败：" + target.absolutePath }
        }
    }

    /**
     * 当前活动目录：指针优先，指针缺失/失效时回退到最新版本目录并顺手修好指针；
     * 没有 versions 的老扁平目录直接返回自身。
     */
    fun activeDir(id: String): File? {
        val dir = idDir(id)
        if (!dir.isDirectory) return null
        val versions = File(dir, "versions")
        if (!versions.isDirectory) {
            return dir.takeIf { it.listFiles()?.isNotEmpty() == true }
        }
        val pointed = readPointer(id)?.let { File(versions, it) }
        if (pointed != null && pointed.isDirectory) return pointed
        val latest = latestVersionDir(versions) ?: return null
        runCatching { writePointer(id, latest.name) }
        return latest
    }

    fun listIds(): List<String> {
        val fromDisk = root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .map { it.name }
        return (registry.ids() + fromDisk).distinct().sorted()
    }

    private fun latestVersionDir(versions: File): File? =
        versions.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .maxWithOrNull(compareBy({ sortKey(it.name) }, { it.lastModified() }))

    /** 把 1.10.0 排到 1.9.0 之后：每段数字左补零成等长字符串，字符串比较即版本比较。 */
    private fun sortKey(name: String): String =
        name.split('.', '-', '_').joinToString(".") { part ->
            (part.takeWhile { it.isDigit() }.toIntOrNull() ?: -1).toString().padStart(10, '0')
        }

    // ------------------------------------------------------------ 安装入口

    /** 从本地文件安装：按文件头区分 zip / tgz。 */
    suspend fun installArchive(file: File, source: String? = null): PackageResult {
        val staged = try {
            stage(file)
        } catch (t: Throwable) {
            val message = "解包失败：" + (t.message ?: t.toString())
            appendLog("ERROR archive " + file.name + " " + message)
            stackLines(t).forEach { appendLog(it) }
            return PackageResult(
                false,
                error = message,
                log = listOf("source=" + (source ?: file.name), message) + stackLines(t),
            )
        }
        return installStaged(staged, source ?: file.name)
    }

    /** 从已经解开的目录安装（测试与「从工作区目录装」共用同一条事务路径）。 */
    suspend fun installFromDirectory(dir: File, source: String? = null): PackageResult {
        val stagedDir = newStagingDir()
        try {
            dir.copyRecursively(stagedDir, overwrite = true)
            val found = findManifest(stagedDir)
                ?: error(NO_MANIFEST_MESSAGE)
            return installStaged(StagedPackage(stagedDir, found.first, found.second), source ?: dir.absolutePath)
        } catch (t: Throwable) {
            stagedDir.deleteRecursively()
            val message = t.message ?: t.toString()
            appendLog("ERROR directory " + dir.name + " " + message)
            stackLines(t).forEach { appendLog(it) }
            return PackageResult(false, error = message, log = listOf(message) + stackLines(t))
        }
    }

    /** 核心事务：校验 → 备份 → 落版本 → 换指针 → 激活 → 写注册表。 */
    internal suspend fun installStaged(staged: StagedPackage, source: String? = null): PackageResult {
        val log = mutableListOf<String>()
        var id = ""
        var version = ""
        try {
            log += "source=" + (source ?: "local")
            val parsed = PluginManifestFile.parse(staged.manifestText).getOrElse {
                throw IllegalArgumentException("plugin.json 解析失败：" + (it.message ?: it.toString()))
            }
            id = parsed.id
            version = parsed.version
            validatePluginId(id)?.let { throw IllegalArgumentException(it) }
            validateVersion(version)?.let { throw IllegalArgumentException(it) }
            verifyTree(staged.dir)
            // npm 包从不打包 node_modules，缺依赖是常态：只记一条 warn 让包装上，
            // 运行期加载失败会降级成 JsBrokenPlugin（卡片上直接显示缺什么）。
            val missingDeps = missingDependencies(staged)
            if (missingDeps.isNotEmpty()) log += "warn deps=" + missingDeps.joinToString("、")
            onVerify(id, staged.dir)
            log += "verify ok id=" + id + " version=" + version
            val active = commit(parsed, staged.manifestText, staged.dir, log)
            // package.json 必须随包留在落盘目录：DeclarativePlugin 与 JS 运行期都靠它认包身份/入口，
            // 解包时把它清掉就会出现「装上了但用不了」。装的是 npm 形态（带 package.json）就必须还在。
            if (staged.sourcePackage != null && !File(active, "package.json").isFile) {
                throw IllegalStateException("package.json 没有留在落盘目录：" + active.absolutePath)
            }
            log += "manifests kept=" + PluginPackageFormat.manifestFiles(active).joinToString("、") { it.name }
            clearError(id)
            appendLog("OK install " + id + "@" + version + " -> " + active.absolutePath)
            return PackageResult(
                ok = true,
                id = id,
                version = version,
                path = active.absolutePath,
                active = true,
                log = log,
                manifest = parsed,
            )
        } catch (t: Throwable) {
            staged.dir.deleteRecursively()
            val message = t.message ?: t.toString()
            // 安装/启用失败要能被定位：完整堆栈一份进 UI 日志（log），一份进 .errors/<id>.txt，
            // 再一份落 install.log（只留前若干行，免得把 200 行的尾巴冲掉）。
            val stack = stackLines(t)
            log += "failed: " + message
            log += stack
            recordError(id.ifBlank { "unknown" }, message + "\n" + t.stackTraceToString())
            appendLog("ERROR install " + id.ifBlank { "?" } + " " + message)
            stackLines(t, STACK_LOG_LINES).forEach { appendLog(it.trim()) }
            return PackageResult(
                ok = false,
                id = id,
                version = version,
                error = message,
                log = log,
                active = id.isNotBlank() && activeDir(id) != null,
            )
        }
    }

    private suspend fun commit(
        parsed: PluginManifestFile,
        manifestText: String,
        stagedDir: File,
        log: MutableList<String>,
    ): File {
        val id = parsed.id
        val dir = idDir(id)
        dir.mkdirs()
        val previousRaw = registry.raw(id)
        val previousVersion = previousRaw
            ?.let { runCatching { JSONObject(it).optString("version") }.getOrNull() }
            .orEmpty()
        ensureVersioned(id, previousVersion.ifBlank { parsed.version }, log)

        val versions = File(dir, "versions")
        versions.mkdirs()
        val versionName = safeSegment(parsed.version)
        val newDir = File(versions, versionName)
        val previousPointer = readPointer(id)

        // 同版本重装：先把旧目录挪出去当备份，失败时放回去。
        val backup = File(stagingRoot, "backup-" + safeSegment(id) + "-" + versionName + "-" + clock())
        if (newDir.exists()) {
            backup.parentFile?.mkdirs()
            backup.deleteRecursively()
            val moved = newDir.renameTo(backup)
            if (!moved) {
                newDir.copyRecursively(backup, overwrite = true)
                newDir.deleteRecursively()
            }
            log += "backup=" + backup.name
        }

        try {
            if (!stagedDir.renameTo(newDir)) {
                newDir.mkdirs()
                stagedDir.copyRecursively(newDir, overwrite = true)
                stagedDir.deleteRecursively()
            }
            log += "payload=versions/" + versionName
            writePointer(id, versionName)
            log += "pointer=" + versionName
            onActivate(id, newDir)
            log += "activate ok"
            registry.put(id, manifestText)
            log += "registry=committed"
        } catch (t: Throwable) {
            // 回滚：恢复旧指针 -> 删掉新 payload -> 把备份放回去。旧版本目录全程没被删过。
            runCatching {
                if (previousPointer != null) writePointer(id, previousPointer) else pointerFile(id).delete()
            }
            newDir.deleteRecursively()
            if (backup.exists()) {
                if (!backup.renameTo(newDir)) {
                    backup.copyRecursively(newDir, overwrite = true)
                    backup.deleteRecursively()
                }
            }
            log += "rollback: " + (t.message ?: t.toString())
            throw t
        }
        backup.deleteRecursively()
        return newDir
    }

    /** 把老扁平目录迁进 versions/<旧版本>，之后所有读路径都走指针。 */
    private fun ensureVersioned(id: String, legacyVersion: String, log: MutableList<String>) {
        val dir = idDir(id)
        val versions = File(dir, "versions")
        if (versions.isDirectory) {
            if (readPointer(id) == null) {
                latestVersionDir(versions)?.let {
                    runCatching { writePointer(id, it.name) }
                    log += "pointer healed=" + it.name
                }
            }
            return
        }
        val children = dir.listFiles().orEmpty().filter { it.name != "versions" }
        versions.mkdirs()
        if (children.isEmpty()) return
        val target = File(versions, safeSegment(legacyVersion.ifBlank { "0.0.0" }))
        target.mkdirs()
        children.forEach { child ->
            val dest = File(target, child.name)
            if (dest.exists()) dest.deleteRecursively()
            if (!child.renameTo(dest)) {
                child.copyRecursively(dest, overwrite = true)
                child.deleteRecursively()
            }
        }
        writePointer(id, target.name)
        log += "migrated flat -> versions/" + target.name
    }

    // ---------------------------------------------------------- 卸载 / 修复

    /** 卸载：文件删成功才清注册表（配置随之清掉），删失败保留一切。 */
    fun remove(id: String): PackageResult {
        val result = uninstallDir(idDir(id), id) {
            registry.remove(id)
            clearError(id)
        }
        return result.fold(
            onSuccess = {
                appendLog("OK remove " + id)
                PackageResult(ok = true, id = id, active = false, log = listOf("removed"))
            },
            onFailure = { t ->
                val message = t.message ?: t.toString()
                recordError(id, message)
                appendLog("ERROR remove " + id + " " + message)
                PackageResult(ok = false, id = id, error = message, active = activeDir(id) != null)
            },
        )
    }

    /**
     * 修复：读最近错误 -> 按 source（或当前活动目录）重装同版本。
     *
     * 只重装 payload，不碰注册表里的配置值 —— 用户填的网关地址之类不会丢。
     */
    suspend fun repair(id: String, source: File? = null): PackageResult {
        val log = mutableListOf<String>()
        val info = inspect(id)
        log += "repair id=" + id
        log += "lastError=" + (info.lastError ?: "none")
        val from = source
            ?: activeDir(id)
            ?: latestVersionDir(versionsDir(id))
            ?: return PackageResult(false, id = id, error = "没有可重装的插件源：" + id, log = log)
        if (!from.isDirectory) {
            return PackageResult(false, id = id, error = "插件源不是目录：" + from.absolutePath, log = log)
        }
        log += "source=" + from.absolutePath
        val stagedDir = newStagingDir()
        try {
            from.copyRecursively(stagedDir, overwrite = true)
        } catch (t: Throwable) {
            stagedDir.deleteRecursively()
            val message = t.message ?: t.toString()
            recordError(id, message)
            return PackageResult(false, id = id, error = message, log = log + "copy failed: " + message)
        }
        val found = findManifest(stagedDir)
        if (found == null) {
            stagedDir.deleteRecursively()
            val message = "插件源里没有 plugin.json / package.json：" + from.absolutePath
            recordError(id, message)
            return PackageResult(false, id = id, error = message, log = log + message)
        }
        val result = installStaged(StagedPackage(stagedDir, found.first, found.second), source = "repair:" + from.absolutePath)
        return result.copy(log = log + result.log)
    }

    // -------------------------------------------------------------- 巡检/日志

    fun inspect(id: String): PackageInfo {
        val dir = idDir(id)
        val active = activeDir(id)
        val raw = registry.raw(id)
        val version = raw?.let { runCatching { JSONObject(it).optString("version") }.getOrNull() }.orEmpty()
        val legacy = dir.isDirectory && !File(dir, "versions").isDirectory && active != null
        return PackageInfo(
            id = id,
            installed = raw != null || active != null,
            active = active != null,
            path = (active ?: dir).absolutePath,
            version = version,
            lastError = lastError(id),
            legacy = legacy,
        )
    }

    fun lastError(id: String): String? =
        runCatching { File(errorRoot, safeSegment(id) + ".txt").takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()
            .orEmpty()
            .ifBlank { null }

    fun log(): List<String> =
        runCatching { logFile.takeIf { it.isFile }?.readLines()?.takeLast(200) }.getOrNull().orEmpty()

    private fun recordError(id: String, message: String) {
        runCatching {
            errorRoot.mkdirs()
            File(errorRoot, safeSegment(id) + ".txt").writeText(clock().toString() + " " + message)
        }
    }

    private fun clearError(id: String) {
        runCatching { File(errorRoot, safeSegment(id) + ".txt").delete() }
    }

    /**
     * 失败的完整堆栈，一行一条（带缩进）。UI 回显与 install.log 都用它：
     * 以前只写 t.message，用户只看到「激活失败」四个字，根因全丢。
     */
    private fun stackLines(t: Throwable, limit: Int = MAX_STACK_LINES): List<String> =
        t.stackTraceToString().lineSequence()
            .filter { it.isNotBlank() }
            .take(limit)
            .map { "  " + it.trim() }
            .toList()

    private fun appendLog(line: String) {
        runCatching {
            root.mkdirs()
            logFile.appendText(clock().toString() + " " + line + "\n")
        }
    }

    // ------------------------------------------------------------------ 解包

    internal fun newStagingDir(): File {
        stagingRoot.mkdirs()
        val dir = File(stagingRoot, "stage-" + clock() + "-" + (0..999999).random())
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    /** 解到 staging 并定位清单；失败不留目录。 */
    internal fun stage(file: File): StagedPackage {
        val dir = newStagingDir()
        try {
            val magic = file.inputStream().use { head ->
                val buf = ByteArray(2)
                val n = head.read(buf)
                if (n == 2 && buf[0] == 0x1f.toByte() && buf[1] == 0x8b.toByte()) "gz" else "zip"
            }
            if (magic == "gz") {
                file.inputStream().use { input -> TarGz.extract(input, dir) }
            } else {
                file.inputStream().use { input -> unpackZip(input, dir) }
            }
            val found = findManifest(dir)
                ?: error(NO_MANIFEST_MESSAGE)
            return StagedPackage(dir, found.first, found.second)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    /**
     * 找清单：最浅的 plugin.json 优先，否则用 package.json 合成（npm 形态）。
     * 返回 (清单文本, 原始 package.json 或 null)。
     */
    internal fun findManifest(root: File): Pair<String, JSONObject?>? {
        val all = root.walkTopDown().filter { it.isFile }.toList()
        val pluginJson = all.filter { it.name == "plugin.json" }
            .minByOrNull { it.relativeTo(root).path.count { c -> c == File.separatorChar } }
        if (pluginJson != null) {
            // 先读后搬：flatten 会删掉清单原来的目录层级。
            val text = runCatching { pluginJson.readText() }
                .getOrElse { error("读取 plugin.json 失败：" + it.message) }
            flatten(root, pluginJson.parentFile)
            return text to null
        }
        val pkg = all.filter { it.name == "package.json" }
            .minByOrNull { it.relativeTo(root).path.count { c -> c == File.separatorChar } }
            ?: return null
        // 先读再搬：flatten 会把 pkg 所在目录的内容提到包根（并删掉原目录）。
        val text = runCatching { pkg.readText() }
            .getOrElse { error("读取 package.json 失败：" + it.message) }
        flatten(root, pkg.parentFile)
        val json = runCatching { JSONObject(text) }
            .getOrElse { error("package.json 解析失败：" + it.message) }
        return synthesize(json).toString() to json
    }

    /** 把 dir 下的内容提到包根（npm 包与 GitHub zipball 都多一层目录）。 */
    private fun flatten(root: File, dir: File) {
        if (dir.canonicalFile == root.canonicalFile) return
        val children = dir.listFiles().orEmpty()
        children.forEach { child ->
            val dest = File(root, child.name)
            if (dest.exists()) dest.deleteRecursively()
            if (!child.renameTo(dest)) {
                child.copyRecursively(dest, overwrite = true)
                child.deleteRecursively()
            }
        }
        generateSequence(dir) { it.parentFile }
            .takeWhile { it.canonicalFile != root.canonicalFile }
            .toList()
            .forEach { runCatching { if (it.listFiles().isNullOrEmpty()) it.delete() } }
    }

    private fun unpackZip(input: InputStream, target: File) {
        val rootPath = target.canonicalPath
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    // zip-slip / 盘符 / 绝对路径：写盘之前就拒绝。
                    if (name.startsWith("/") || name.startsWith("\\") || DRIVE_LETTER.containsMatchIn(name)) {
                        error("包内路径非法：" + name)
                    }
                    if (name.split('/', '\\').any { it == ".." }) error("包内路径越界：" + name)
                    val out = File(target, name).canonicalFile
                    require(out.path == rootPath || out.path.startsWith(rootPath + File.separator)) {
                        "包内路径越界：" + name
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** 落盘后复查：任何一项 canonical 路径跑出 staging 就整包拒绝。 */
    private fun verifyTree(dir: File) {
        val rootPath = dir.canonicalPath
        dir.walkTopDown().forEach { file ->
            val rel = file.relativeTo(dir).path
            if (rel.isEmpty()) return@forEach
            if (rel.split(File.separatorChar).any { it == ".." }) error("包内路径越界：" + rel)
            if (DRIVE_LETTER.containsMatchIn(rel)) error("包内路径含盘符：" + rel)
            val canon = file.canonicalPath
            require(canon == rootPath || canon.startsWith(rootPath + File.separator)) {
                "包内路径越界：" + rel
            }
        }
    }

    /** 包里找不到的 dependencies；node: 与 @deepseek-ai/ 由宿主提供，不算缺。 */
    private fun missingDependencies(staged: StagedPackage): List<String> {
        val deps = staged.sourcePackage?.optJSONObject("dependencies") ?: return emptyList()
        return deps.keys().asSequence()
            .filterNot { it.startsWith("node:") || it.startsWith("@deepseek-ai/") }
            .filterNot { File(File(staged.dir, "node_modules"), it).exists() }
            .toList()
    }

    /**
     * 由 npm 的 package.json 合成一份 plugin.json。
     *
     * 目标不是把 web 插件变成原生插件，而是让装上的东西在界面里说得清、点得动。
     */
    internal fun synthesize(pkg: JSONObject): JSONObject {
        val name = pkg.optString("name").ifBlank { "unnamed-plugin" }
        val id = "npm." + name.removePrefix("@").replace('/', '.')
        val title = pkg.optString("displayName").ifBlank { pkg.optString("name").substringAfterLast('/') }
        val version = pkg.optString("version").ifBlank { "0.0.0" }
        val description = pkg.optString("description")
        val contributions = JSONArray()
        if (description.isNotBlank()) {
            val children = JSONArray()
            children.put(JSONObject().put("type", "text").put("text", description))
            contributions.put(
                JSONObject()
                    .put("slot", "settings.section")
                    .put("id", "about")
                    .put("label", title)
                    .put("subtitle", version)
                    .put(
                        "ui",
                        JSONObject()
                            .put("width", "fill")
                            .put(
                                "root",
                                JSONObject()
                                    .put("type", "section")
                                    .put("title", title)
                                    .put("subtitle", description)
                                    .put("children", children),
                            ),
                    ),
            )
        }
        return JSONObject()
            .put("id", id)
            .put("name", title)
            .put("version", version)
            .put("description", description)
            .put("author", pkg.optString("author"))
            .put("contributions", contributions)
            .put("config", JSONArray())
    }

    companion object {
        /** 包里既没有 plugin.json 也没有 package.json。zip 是正常分发格式，旧 .apk 也收。 */
        internal const val NO_MANIFEST_MESSAGE: String =
            "包里没有 plugin.json 或 package.json：插件包请用 .zip（里面放 package.json + 入口，或 plugin.json）；旧 .apk 插件包也收"

        /** 失败堆栈进 UI 日志的行数上限（够看到 throw 站点，又不会淹掉日志）。 */
        internal const val MAX_STACK_LINES: Int = 60

        /** 失败堆栈进 install.log 的行数上限（install.log 只留 200 行尾巴）。 */
        internal const val STACK_LOG_LINES: Int = 12
    }
}
