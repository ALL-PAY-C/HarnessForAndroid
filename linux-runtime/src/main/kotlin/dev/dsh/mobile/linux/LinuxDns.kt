package dev.dsh.mobile.linux

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * T5：挑一个「真的会应答」的 DNS。
 *
 * 不信任设备属性：Android 15 上 getprop net.dns1 已经是空的。
 * 不硬编码公网 DNS：模拟器 NAT 只放行 10.0.2.3，写死 8.8.8.8 反而会让
 * musl 的 getaddrinfo 整体失败（EAI_AGAIN）。所以对每个候选发一次真 UDP 查询，
 * 正常只保留应答的，最多写 2 个；全失败时由 LinuxNetwork 明确标记降级。
 */
object LinuxDns {

    /** 优先业务名 A；部分 VPN 转发器不接受根区 NS 查询。 */
    const val PROBE_NAME = "example.com"
    const val PROBE_QTYPE = 1 // A

    private const val QCLASS_IN = 1
    private const val PROBE_TIMEOUT_MS = 800
    private const val MAX_SERVERS = 2

    /** 系统/设备来源之后依次尝试模拟器、国内与国际公共 DNS，全部经探测。 */
    val FALLBACK = listOf("10.0.2.3", "223.5.5.5", "114.114.114.114", "8.8.8.8", "1.1.1.1")
    val DEGRADED = listOf("223.5.5.5", "114.114.114.114")

    /** 只接受 IPv4 字面量；不触发宿主解析，保留系统网络顺序。 */
    fun ipv4Servers(servers: List<String>): List<String> = servers.map { it.trim() }.filter { server ->
        val parts = server.split('.')
        parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } && !server.startsWith("127.")
    }.distinct()

    private val propNames = listOf("net.dns1", "net.dns2", "net.dns3", "net.dns4")

    @Volatile
    private var cache: List<String>? = null

    /** 读 net.dns1..4；getprop 不可用（单测、非 Android）时返回空表。 */
    fun deviceProps(run: (String) -> String? = ::runGetprop): List<String> =
        propNames.mapNotNull { run(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }

    /** 候选去重 → 实测 → 取前 [limit] 个。纯函数，探测函数可注入以便单测。 */
    fun resolve(
        props: List<String> = deviceProps(),
        fallback: List<String> = FALLBACK,
        probe: (String) -> Boolean = { answers(it) },
        limit: Int = MAX_SERVERS,
    ): List<String> = (props + fallback)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .filter(probe)
        .take(limit)

    /** 带缓存：ProotChannel 每次构造都会问一次，探测只做一遍。 */
    fun resolveCached(): List<String> = cache ?: resolve().also { cache = it }

    /**
     * 真发一次 UDP 查询：有应答、QR=1、rcode=0 才算可用。
     *
     * 主线程一律判不可用：这是真 socket 发送，StrictMode 直接抛
     * NetworkOnMainThreadException 把进程打死（真机点 Agent 页即中招）。
     */
    fun answers(server: String, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean {
        if (onMainThread()) return false
        return answersQuery(server, query(), timeoutMs) || answersQuery(server, query(".", 2), timeoutMs)
    }

    /** 主线程判定，单测可替换：JVM 里 android.os.Looper 是桩（调用会抛 not mocked）。 */
    internal var mainThreadProbe: () -> Boolean = ::looperMainThread

    private fun onMainThread(): Boolean = runCatching { mainThreadProbe() }.getOrDefault(false)

    private fun looperMainThread(): Boolean {
        val main = android.os.Looper.getMainLooper()
        return main != null && android.os.Looper.myLooper() === main
    }

    private fun answersQuery(server: String, q: ByteArray, timeoutMs: Int): Boolean = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(512)
            socket.receive(DatagramPacket(buf, buf.size))
            (buf[2].toInt() and 0x80) != 0 && (buf[3].toInt() and 0x0F) == 0
        }
    } catch (e: IOException) {
        false
    }

    /** 拼一个最小 DNS 查询报文（header + qname + qtype/qclass）。 */
    fun query(name: String = PROBE_NAME, qtype: Int = PROBE_QTYPE): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x4d); out.write(0x53)          // id
        out.write(0x01); out.write(0x00)          // RD
        out.write(0x00); out.write(0x01)          // qdcount = 1
        repeat(6) { out.write(0) }                // an/ns/ar = 0
        if (name == ".") {
            out.write(0)
        } else {
            name.split('.').forEach { label ->
                out.write(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
            out.write(0)
        }
        out.write(qtype shr 8); out.write(qtype and 0xFF)
        out.write(QCLASS_IN shr 8); out.write(QCLASS_IN and 0xFF)
        return out.toByteArray()
    }

    private fun runGetprop(name: String): String? = try {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
