package dev.dsh.mobile.linux

/**
 * proot 下 apk 的 unlink+rename 时序会失败：ncurses-terminfo-base 的 terminfo/v/vt220、
 * unzip 的 usr/bin/zipinfo 各抛一条 ERROR，但包其实都进了数据库。
 *
 * 这两行永远不进日志与 UI——它们没有诊断价值，装失败时看 apk 的其他 ERROR 行就够。
 * 所以这里是无条件的唯一入口：apk 输出要展示（状态、错误、日志文件）就必须过它。
 * 只有包都装好时，才顺带把 apk 结尾的 `3 errors; ...` 汇总行也剔掉。
 */
object LinuxApkLog {

    /** apk 原始行形态：`ERROR: <pkg>: failed to rename <src> to <dst>.`（部分版本 dst 后直接跟 `: <reason>`）。 */
    private val renameRe = Regex("""failed\s+to\s+rename\s+(\S+)\s+to\s+(\S+)""")

    /** rename 失败后紧跟的那条 `ERROR: <pkg>: Permission denied`（整行只此一句）。 */
    private val permissionCompanionRe = Regex("""^\s*(?:ERROR:\s*)?\S+:\s+Permission\s+denied\.?\s*$""", RegexOption.IGNORE_CASE)

    /** apk 末尾的 `3 errors; 88 MiB in 53 packages` 汇总行。 */
    private val summaryRe = Regex("""^\s*\d+\s+errors?\s*;.*$""")

    /** 已知无害的 rename 目标：包已注册，落不了盘也不影响功能。 */
    private val benignTargets = listOf("etc/terminfo/v/vt220", "usr/bin/zipinfo")

    /**
     * @param allPackagesInstalled apk 装的包是否都进了数据库（不是看 apk 退出码）。
     *   这条只决定汇总行去留；两条已知无害的 rename 行无条件剔除。
     */
    fun filterBenignApkErrors(log: String, allPackagesInstalled: Boolean): String {
        if (log.isEmpty()) return log
        val lines = splitLines(log)
        val noise = BooleanArray(lines.size) { isBenignRename(lines[it]) }
        for (i in 1 until lines.size) {
            // apk 紧接着补一条 `ERROR: <pkg>: Permission denied`，同属这一条失败；整行只有它才算，
            // 带别的说明的 permission denied 照旧留给用户看。
            if (noise[i - 1] && permissionCompanionRe.matches(lines[i])) noise[i] = true
        }
        val realError = lines.indices.any { !noise[it] && lines[it].contains("ERROR") }
        val dropSummary = allPackagesInstalled || !realError
        val kept = ArrayList<String>(lines.size)
        for (i in lines.indices) {
            if (noise[i]) continue
            if (dropSummary && summaryRe.matches(lines[i])) continue
            kept += lines[i]
        }
        return if (kept.size == lines.size) log else kept.joinToString("\n")
    }

    /** 按 \n 切行，顺带吃掉 CR/LF 差异（proot 的管道偶发 \r\n）。 */
    private fun splitLines(log: String): List<String> =
        log.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /** 只认这两个已知无害的目标；别的 rename 失败照旧留给用户看。 */
    private fun isBenignRename(line: String): Boolean {
        if (!line.contains("ERROR")) return false
        val target = renameRe.find(line)?.groupValues?.get(2) ?: return false
        val path = target.trim().trimEnd('.', ',', ':', ';').removePrefix("/").removeSuffix("/")
        return benignTargets.any { path == it || path.endsWith("/" + it) }
    }
}
