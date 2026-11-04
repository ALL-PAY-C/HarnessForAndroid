package dev.dsh.mobile.app.diag

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.diag.LkgPlanner
import dev.dsh.mobile.core.common.diag.SettingsSnapshot
import java.io.File

/**
 * LKG 快照的落盘层（filesDir/lkg，T9.12 §3.3）。
 *
 * 环形 3 份 settings-v<N>.json：只留 3 份是因为回滚只需要「上一份」，再多只是把
 * 「用户手动撤销」的空间拉长到无法追溯；另存一份 pre-rollback.json 审计副本，
 * 供诊断页「恢复上次配置」把回滚本身撤销掉。
 *
 * 写盘一律 tmp + rename：写快照时进程可能正处在崩溃循环里，半截 JSON 会让下一次
 * 启动读不出快照、回滚链直接断。
 */
class LkgStore(private val dir: File) {

    /**
     * 写一份快照。内容 hash 与最新一份相同时跳过（返回 false）：
     * 每次启动都写一份同内容快照只会把环形窗口里的历史真快照挤掉。
     */
    fun writeSnapshot(entries: Map<String, String>, versionCode: Int, at: Long = System.currentTimeMillis()): Boolean {
        val latest = latest()
        if (latest != null && latest.hash == LkgPlanner.hashOf(entries)) return false
        val version = nextVersion()
        val snapshot = SettingsSnapshot.of(version, at, versionCode, entries)
        val ok = writeAtomic(fileFor(version), snapshot.toJson())
        if (ok) {
            prune()
            AppLog.i("LKG_SNAPSHOT v" + version + " keys=" + entries.size + " hash=" + snapshot.hash.take(8))
        } else {
            AppLog.w("LKG_SNAPSHOT_FAILED v" + version + " dir=" + dir.absolutePath)
        }
        return ok
    }

    /** 最新一份；目录不存在或全损坏时返回 null。 */
    fun latest(): SettingsSnapshot? = versions().lastOrNull()?.let { read(it) }

    /** 倒数第二份：回滚目标（最新一份通常就是崩前那份坏配置的同代快照）。 */
    fun previous(): SettingsSnapshot? = versions().dropLast(1).lastOrNull()?.let { read(it) }

    /** 回滚前的当前配置（用户可撤销回滚）。 */
    fun preRollback(): SettingsSnapshot? = readFile(File(dir, PRE_ROLLBACK))

    fun savePreRollback(snapshot: SettingsSnapshot): Boolean {
        val ok = writeAtomic(File(dir, PRE_ROLLBACK), snapshot.toJson())
        if (ok) AppLog.i("LKG_PRE_ROLLBACK v" + snapshot.version + " keys=" + snapshot.entries.size)
        return ok
    }

    /** 下一个版本号：现存最大值 + 1（不按时间排序，同一毫秒连写两份时钟会相等）。 */
    fun nextVersion(): Int = (versions().lastOrNull() ?: 0) + 1

    /** 现有快照版本号，升序。 */
    fun versions(): List<Int> = runCatching {
        dir.listFiles { f -> f.isFile && NAME.matches(f.name) }
            ?.mapNotNull { NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            ?.sorted()
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun prune() {
        val all = versions()
        if (all.size <= KEEP) return
        all.dropLast(KEEP).forEach { runCatching { fileFor(it).delete() } }
    }

    private fun fileFor(version: Int) = File(dir, "settings-v" + version + ".json")

    private fun read(version: Int): SettingsSnapshot? = readFile(fileFor(version))

    private fun readFile(file: File): SettingsSnapshot? =
        runCatching { SettingsSnapshot.fromJson(file.readText(Charsets.UTF_8)) }.getOrNull()

    private fun writeAtomic(target: File, text: String): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        val tmp = File(dir, target.name + ".part")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Windows/部分 FUSE 上 rename 不带替换语义：先删目标再试一次。
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@runCatching false
            }
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val KEEP = 3
        const val PRE_ROLLBACK = "pre-rollback.json"
        val NAME = Regex("settings-v(\\d+)\\.json")
    }
}
