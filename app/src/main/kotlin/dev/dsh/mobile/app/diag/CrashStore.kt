package dev.dsh.mobile.app.diag

import java.io.File
import java.util.UUID

/**
 * 崩溃落盘目录（默认 `filesDir/crash`）。
 *
 * 写盘只走「临时文件 + rename」：崩溃现场随时可能掉电或被系统杀，直接 writeText 到目标名
 * 会留下半截 JSON，而半截文件在下次启动解析时既读不出记录又占着配额。
 */
class CrashStore(private val dir: File) {

    fun write(record: CrashRecord): File? = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
        val name = PREFIX + record.ts + "-" + UUID.randomUUID().toString().replace("-", "").take(8) + SUFFIX
        val target = File(dir, name)
        val tmp = File(dir, name + ".part")
        tmp.writeText(record.toJson(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Windows/部分 FUSE 上 rename 不带替换语义：先删目标再试一次。
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@runCatching null
            }
        }
        target
    }.getOrNull()

    /** 新→旧。文件名带 epochMs，但用 lastModified 排序更稳（rename 会保留写入时间）。 */
    fun list(): List<File> = runCatching {
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun read(file: File): CrashRecord? =
        runCatching { CrashRecord.fromJson(file.readText(Charsets.UTF_8)) }.getOrNull()

    /** 先删过期，再按条数从旧到新截断。任何一步失败都只影响清理，不影响已落盘的记录。 */
    fun prune(max: Int, maxAgeDays: Int) {
        runCatching {
            val cutoff = System.currentTimeMillis() - maxAgeDays * DAY_MS
            list().forEach { if (it.lastModified() in 1 until cutoff) it.delete() }
            list().drop(max).forEach { it.delete() }
        }
    }

    fun delete(file: File) {
        runCatching { file.delete() }
    }

    private companion object {
        const val PREFIX = "crash-"
        const val SUFFIX = ".json"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
