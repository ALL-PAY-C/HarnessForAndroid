package dev.dsh.mobile.core.data.secret

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * endpoint token 的独立存储，落在 [Context.noBackupFilesDir]（`files/no_backup/`）。
 *
 * 安全性边界（务必准确理解）：`noBackupFilesDir` 只表示「不进入 Android Auto Backup，也
 * 不在 BackupManager 手动 zip 的打包范围内」，**不是静态加密**——文件在磁盘上仍是明文 JSON。
 *
 * 线程安全：同一 canonical 文件跨实例共享一把锁（[lockFor]），杜绝多实例并发读改写丢更新；
 * 写采用「唯一临时文件 + Files.move(ATOMIC_MOVE, REPLACE_EXISTING)」原子替换，移动失败抛异常且保留原件（不直接覆盖）。
 */
class EndpointSecretStore(private val dir: File) {

    constructor(context: Context) : this(context.noBackupFilesDir)

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(dir, FILE_NAME)
    private val lock: Any get() = lockFor(file)

    fun get(id: String): String? = synchronized(lock) {
        runCatching { read()[id] }.getOrNull()
    }

    fun set(id: String, token: String) {
        synchronized(lock) {
            val map = read() // 读坏文件会抛异常 → 不覆盖，异常上抛让调用方保留旧值/可重试
            map[id] = token
            write(map)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val map = read()
            map.remove(id)
            write(map)
        }
    }

    fun all(): Map<String, String> = synchronized(lock) {
        runCatching { read().toMap() }.getOrDefault(emptyMap())
    }

    /** 严格读：文件不存在返回空；读/解析失败抛异常（写路径据此避免静默覆盖坏文件）。 */
    private fun read(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        val text = file.readText(Charsets.UTF_8) // IOException 上抛
        return json.decodeFromString<Map<String, String>>(text).toMutableMap() // 解析错上抛
    }

    private fun write(map: Map<String, String>) {
        if (!dir.exists() && !dir.mkdirs()) error("cannot create secret dir: $dir")
        val bytes = json.encodeToString(map).toByteArray(Charsets.UTF_8)
        val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
        try {
            tmp.writeBytes(bytes)
            try {
                // 原子替换（覆盖已存在目标）。Windows `File.renameTo` 不能覆盖已有目标，
                // `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 跨平台原子覆盖（minSdk=26，`java.nio.file` 可用）。
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // 文件系统不支持原子移动：失败并保留原件（不降级到非原子 move，也不直接覆盖）。
                throw IllegalStateException("atomic move not supported for secret file: ${file.name}", e)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {
        private const val FILE_NAME = "endpoint-tokens.json"
        /** 同一 canonical 文件跨实例共享锁，杜绝多实例并发读改写丢 token。 */
        private val LOCKS = ConcurrentHashMap<String, Any>()
        private fun lockFor(f: File): Any {
            val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            return LOCKS.computeIfAbsent(key) { Any() }
        }
    }
}

/** 合并 token：secret 优先，缺失时回退 legacy（Room 旧字段）。 */
internal fun mergeToken(legacy: String, secret: String?): String = secret ?: legacy

/** 是否应把 legacy 迁移进 secret：legacy 非空且 secret 尚未有值（同设备恢复时不覆盖当前 secret）。 */
internal fun shouldMigrate(legacy: String, secret: String?): Boolean =
    legacy.isNotBlank() && secret == null
