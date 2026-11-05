package dev.dsh.mobile.pluginhost

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 本机回环端口分配（B1）。
 *
 * 插件要起本地服务时，端口是稀缺资源：两个反代插件都写 8317 就会互相踩。
 * 这里只做一件最小的事 —— 从首选端口起逐个试探，占用了就 +1，连续试 [MAX_PROBES] 个；
 * 只有 [LOOPBACK] 一个地址参与，不关心 0.0.0.0 上的占用（那不是本机服务会绑的东西）。
 *
 * 纯 JVM，无 Android 依赖：单测直接开真 socket 验证「占用顺延 / 释放回落」，不需要模拟器。
 */
object LoopbackPorts {

    /** 只探回环：插件服务的调用方永远是本机 127.0.0.1。 */
    const val LOOPBACK: String = "127.0.0.1"

    /** 一次分配最多往后挪几个端口；全占满就返回 -1，让调用方如实报错而不是静默换到奇怪的端口。 */
    const val MAX_PROBES: Int = 32

    /** 没有历史记录时的首选端口（多数本机反代的习惯端口）。 */
    const val DEFAULT_PREFERRED: Int = 8317

    /** 插件 config 里存端口用的键：与 plugin.json 里插件的 `port` 声明互不干扰。 */
    const val CONFIG_KEY: String = "loopback.port"

    private val PORT_RANGE = 1..65535

    /**
     * 从 [preferred] 起找一个能绑的端口；全占满返回 -1。
     *
     * 非 1..65535 的 [preferred] 直接判失败：越界的「可用端口」比失败更难排查。
     */
    fun allocate(preferred: Int): Int {
        if (preferred !in PORT_RANGE) return -1
        val last = minOf(preferred + MAX_PROBES - 1, PORT_RANGE.last)
        for (port in preferred..last) {
            if (isFree(port)) return port
        }
        return -1
    }

    /**
     * 端口能不能绑上。
     *
     * 显式 `reuseAddress = false`：Windows 上 SO_REUSEADDR 会允许绑定已被占用的端口，
     * 那样「占用探测」永远返回 true，分配逻辑等于没写。
     */
    fun isFree(port: Int): Boolean {
        if (port !in PORT_RANGE) return false
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = false
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 1)
            }
        }.getOrNull() ?: return false
        runCatching { socket.close() }
        return true
    }
}

/**
 * 端口登记器：把「插件当前用哪个端口」这件事从分配动作里拆出来。
 *
 * 两条语义同时成立：
 * 1. **已分配即稳定**：插件一旦拿到端口，宿主不再拿 isFree 去复核它 —— 复核必然失败，
 *    占用者就是插件自己的服务；以前每次周期探测都把端口往后挪一格并改写 config，
 *    结果是「存储说 8318、服务在 8317」。
 * 2. **进程内不重复发放**：issued 记录本进程已经发出去的端口，探测必须同时满足「不在 issued」
 *    与「isFree」。少了这一条，两个插件连着要同一段端口时会拿到同一个空闲值 —— 骨架插件都不
 *    绑定，isFree 永远为真；B3/B4 真绑定时也只是把窗口缩小（A 还没绑完 B 就开始探测），
 *    并发 ensureStarted 照样撞。
 *
 * 三条出口各管一件事：port 只做一次性分配，reallocate 先释放旧的再分新的，forget（禁用/卸载）
 * 释放占用。读、写、分配都是注入的函数，单测用内存 map + 真 socket 就能覆盖全部分支。
 */
internal class LoopbackPortRegistry(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val allocate: (Int) -> Int = LoopbackPorts::allocate,
) {

    /** 进程内缓存：config 读在 B2 之后是解密路径（PBKDF2 派生的结果被缓存，但仍不该每帧都读）。 */
    private val cache = HashMap<String, Int>()

    /** 本进程已经发出去的端口：别的插件不得再拿。forget / reallocate 时释放。 */
    private val issued = HashSet<Int>()

    /** 该插件现在用哪个端口。有记录（缓存或 config）就直接返回，不探测。 */
    fun port(pluginId: String, preferred: Int): Int {
        cache[pluginId]?.let { return it }
        val stored = read(pluginId)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
        // 存储里的端口如果已经被本进程发给别的插件，就当没记录，重新分一个。
        if (stored != null && stored !in issued) {
            reserve(pluginId, stored)
            return stored
        }
        val port = allocateUnissued(preferred)
        remember(pluginId, port)
        return port
    }

    /**
     * 重分配：调用方已确认旧端口不可用（插件报 Address already in use）。
     *
     * 先 release 再分配：旧端口必须腾出 issued 名额，否则每换一次端口就永久损失一个候选；
     * 也避免「新端口恰好等于刚释放的旧端口」时被自己判成重复。分配失败（-1）时不留脏记录，
     * 下次 port 会重新走一次分配。
     */
    fun reallocate(pluginId: String, preferred: Int): Int {
        release(pluginId)
        write(pluginId, "")
        val port = allocateUnissued(preferred)
        remember(pluginId, port)
        return port
    }

    /** 插件被禁用 / 卸载 / 从磁盘消失：缓存与占位一起作废，端口回到可发放池。 */
    fun forget(pluginId: String) {
        release(pluginId)
    }

    /**
     * 从 [from] 起找第一个「没发过且能绑上」的端口。
     *
     * allocate 只会从候选起点开始顺延，撞上已发放端口时从它 +1 继续，整体仍受
     * [LoopbackPorts.MAX_PROBES] 约束 —— 不会因为 issued 里塞满而无限扫。
     */
    private fun allocateUnissued(from: Int): Int {
        var candidate = from
        repeat(LoopbackPorts.MAX_PROBES) {
            val port = allocate(candidate)
            if (port < 0) return -1
            if (port !in issued) return port
            candidate = port + 1
        }
        return -1
    }

    private fun reserve(pluginId: String, port: Int) {
        cache[pluginId] = port
        issued += port
    }

    private fun release(pluginId: String) {
        cache.remove(pluginId)?.let { issued -= it }
    }

    private fun remember(pluginId: String, port: Int) {
        if (port < 0) {
            cache.remove(pluginId)
            return
        }
        write(pluginId, port.toString())
        reserve(pluginId, port)
    }
}
