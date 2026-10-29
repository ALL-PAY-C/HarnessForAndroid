package dev.dsh.mobile.linux

/** linux-artifacts.json 里的一条 rootfs 制品。 */
data class LinuxManifest(
    val version: String,
    val abi: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

/** 固定格式的清单解析：只认 {"version":..,"artifacts":[{"abi","url","sha256","bytes"}]}。 */
object LinuxArtifacts {

    private val objectRe = Regex("\\{[^{}]*\\}")
    private val versionRe = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")

    fun parse(json: String): List<LinuxManifest> {
        val version = versionRe.find(json)?.groupValues?.get(1).orEmpty()
        return objectRe.findAll(json).mapNotNull { m ->
            val body = m.value
            fun str(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
            fun num(key: String) = Regex("\"$key\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
            val abi = str("abi") ?: return@mapNotNull null
            // 同一份清单里还有 proot 段（没有 url，只有 sha256/bytes），rootfs 条目才带 url。
            val url = str("url") ?: return@mapNotNull null
            LinuxManifest(
                version = str("version") ?: version,
                abi = abi,
                url = url,
                sha256 = str("sha256").orEmpty(),
                bytes = num("bytes") ?: 0L,
            )
        }.toList()
    }

    fun forAbi(list: List<LinuxManifest>, abi: String): LinuxManifest? = list.firstOrNull { it.abi == abi }
}
