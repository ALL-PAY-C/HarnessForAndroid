package dev.dsh.mobile.linux

import java.io.File

/**
 * R2.2：apk 镜像探测与落地。
 *
 * 先测延迟再写 /etc/apk/repositories：国内镜像快但线路会抖，探测失败的镜像一律不写进去，
 * 全失败就回官方源——装包可以慢，不能停。
 */
object LinuxApk {

    const val VERSION = "v3.20"
    const val REPOSITORIES_PATH = "etc/apk/repositories"

    /** 单次探测上限：超过就算这条线路不通，不再拖着等。 */
    const val PROBE_TIMEOUT_S = 3

    /** 探测目标用索引而不是首页：索引能拿到才说明真能装包。 */
    const val PROBE_PATH = "main/%s/APKINDEX.tar.gz"

    const val MIRROR_BEGIN = "#APK-MIRROR-BEGIN"
    const val MIRROR_END = "#APK-MIRROR-END"
    const val MIRROR_LINE = "#APK-MIRROR"

    private const val D = "\$"

    data class Mirror(val name: String, val base: String)

    val ALIYUN = Mirror("aliyun", "https://mirrors.aliyun.com/alpine/$VERSION")
    val TUNA = Mirror("tuna", "https://mirrors.tuna.tsinghua.edu.cn/alpine/$VERSION")
    val OFFICIAL = Mirror("official", "https://dl-cdn.alpinelinux.org/alpine/$VERSION")

    /** 只探测国内镜像；官方源不参与探测，直接当回退，免得先多等一次超时。 */
    val CANDIDATES = listOf(ALIYUN, TUNA)
    val FALLBACK = OFFICIAL

    /** proot 里 uname 看到的是 ABI 名，apt/apk 要的是 Alpine 架构名。 */
    fun alpineArch(abi: String): String = when (abi.lowercase()) {
        "arm64", "arm64-v8a", "aarch64" -> "aarch64"
        "x86_64", "amd64", "x64" -> "x86_64"
        "arm", "armv7", "armeabi-v7a" -> "armv7"
        "x86", "i686", "i386" -> "x86"
        else -> abi.lowercase()
    }

    /**
     * 探测脚本：curl 计时打索引文件，失败留空 time。
     * 判定不放在 shell 里，留给 [choose] 做纯函数，好打桩。
     */
    fun probeScript(arch: String, candidates: List<Mirror> = CANDIDATES): String {
        val entries = candidates.joinToString(" ") { "${it.name}|${it.base}" }
        return """
            set -u
            MIRRORS='$entries'
            echo "$MIRROR_BEGIN"
            for entry in ${D}MIRRORS; do
              name=${D}{entry%%|*}
              base=${D}{entry#*|}
              t=${D}(curl -o /dev/null -s -m $PROBE_TIMEOUT_S -w '%{time_total}' "${D}base/${PROBE_PATH.format(arch)}" 2>/dev/null || true)
              echo "$MIRROR_LINE name=${D}name base=${D}base time=${D}t"
            done
            echo "$MIRROR_END"
        """.trimIndent()
    }

    data class Probe(val mirror: Mirror, val seconds: Double?)

    private val probeRe = Regex(MIRROR_LINE + "\\s+name=(\\S+)\\s+base=(\\S+)\\s+time=(\\S*)")

    fun parseProbes(stdout: String): List<Probe> = probeRe.findAll(stdout).map { m ->
        Probe(
            mirror = Mirror(m.groupValues[1], m.groupValues[2]),
            // curl 失败给空串或 0；两者都当「不通」。
            seconds = m.groupValues[3].trim().toDoubleOrNull()?.takeIf { it > 0.0 },
        )
    }.toList()

    /** 最快且可达者胜出；没有任何成功探测就回退官方源。 */
    fun choose(probes: List<Probe>, fallback: Mirror = FALLBACK): Mirror =
        probes.filter { it.seconds != null }.minByOrNull { it.seconds!! }?.mirror ?: fallback

    fun repositoriesContent(base: String): String = "$base/main\n$base/community\n"

    fun applyTo(rootfs: File, base: String): Boolean =
        writeIfChanged(File(rootfs, REPOSITORIES_PATH), repositoriesContent(base))
}
