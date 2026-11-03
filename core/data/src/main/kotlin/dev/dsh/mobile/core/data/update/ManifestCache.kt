package dev.dsh.mobile.core.data.update

import java.io.File

/** 清单这一版是从哪来的。界面/日志靠它区分「刚拉的」与「回退的」。 */
enum class ManifestOrigin { Live, Memory, Disk }

sealed class ManifestResult {
    class Ok(
        val body: String,
        val origin: ManifestOrigin,
        val failures: List<SourceFailure>,
    ) : ManifestResult()

    class Failed(val failures: List<SourceFailure>) : ManifestResult()
}

/**
 * 更新清单的本地副本：内存一级 + 磁盘一级（原子写 + savedAt 时间戳 + TTL）。
 *
 * 为什么留两级：磁盘那次写可能失败（目录不可写、被清理），但同一进程里刚拿到的清单
 * 没有理由丢掉；而跨进程/重启就只剩磁盘那一份。TTL 存在的意义是别把上周的清单
 * 当成今天的判断依据 —— 默认 6h，够覆盖「网络抽风一会儿」又不会骗到隔夜。
 */
class ManifestStore(
    private val file: File?,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    @Volatile
    private var memory: String? = null

    @Volatile
    private var memoryAt: Long = 0L

    /** 内存里的那份；过期就当没有。 */
    fun memory(): String? = memory?.takeIf { !isStale(memoryAt) }

    /** 磁盘那份；文件缺失、格式不认识、过期都返回 null。 */
    fun diskFresh(): String? {
        val f = file ?: return null
        val text = runCatching { if (f.isFile) f.readText() else null }.getOrNull() ?: return null
        val nl = text.indexOf('\n')
        if (nl <= 0) return null
        val savedAt = text.substring(0, nl).removePrefix(SAVED_AT).trim().toLongOrNull() ?: return null
        if (isStale(savedAt)) return null
        return text.substring(nl + 1).takeIf { it.isNotBlank() }
    }

    /** 写内存 + 原子落盘。返回 false 表示没落盘，调用方照常用正文（落盘是尽力而为）。 */
    fun save(body: String): Boolean {
        if (body.isBlank()) return false
        memory = body
        memoryAt = clock()
        val f = file ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            // 先写临时文件再换名：中途被杀掉也不会留下半截 JSON。
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(SAVED_AT + clock() + "\n" + body)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) {
                    tmp.delete()
                    return false
                }
            }
            true
        }.getOrDefault(false)
    }

    /** 时钟回拨（age < 0）也当新鲜：总比没有强。 */
    private fun isStale(savedAt: Long): Boolean = clock() - savedAt > ttlMs

    companion object {
        /** 6 小时：清单只描述「有没有新版」，隔夜之后重拉一次是合理的。 */
        const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L
        private const val SAVED_AT = "savedAt="
    }
}

/**
 * 三级回退链：并行拉取 → 内存 → 磁盘（TTL）→ 把每源原因抛给调用方。
 *
 * 「一个源挂了」不能升级成「整体失败」：只有全挂且本地也没有可用副本时才报错，
 * 报错内容带上每一个源的原因（[describeFailures]）。
 */
class ManifestResolver(
    private val store: ManifestStore,
    private val fetch: suspend (List<String>) -> ManifestAttempt,
) {

    suspend fun resolve(sources: List<String>): ManifestResult {
        val attempt = fetch(sources)
        if (attempt is ManifestAttempt.Ok) {
            store.save(attempt.body)
            return ManifestResult.Ok(attempt.body, ManifestOrigin.Live, attempt.failures)
        }
        val failures = (attempt as ManifestAttempt.Failed).failures
        store.memory()?.let { return ManifestResult.Ok(it, ManifestOrigin.Memory, failures) }
        store.diskFresh()?.let { return ManifestResult.Ok(it, ManifestOrigin.Disk, failures) }
        return ManifestResult.Failed(failures)
    }
}
