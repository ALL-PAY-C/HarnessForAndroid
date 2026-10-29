package dev.dsh.mobile.toolbridge

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 PTY 终端要用的启动参数：可直接喂给 termux 的 JNI.createSubprocess。
 * busybox 可用就用它当 shell（applet 齐全），否则退系统 /system/bin/sh。
 */
data class ShellSpec(
    val shell: String,
    val args: List<String>,
    val cwd: String,
    val env: List<String>,
    val label: String,
)

data class ChannelStatus(
    val id: String,
    val label: String,
    val kind: ChannelKind,
    val available: Boolean,
    val detail: String,
)

/**
 * `channel_list()` 的一行。name 就是模型要写进 `channel` 参数的那个词，
 * reason 是不可用时的原因（未安装 / 未授权 / 服务未运行），必须是给模型看的人话。
 */
data class ChannelListing(
    val name: String,
    val available: Boolean,
    val reason: String,
) {
    fun toJson(): String = MiniJson.obj(
        "name" to name,
        "available" to available,
        "reason" to reason,
    )
}

/**
 * 工具通道注册表：模型要"调用手机上的工具"时，DSH 侧看到的工具就是 [exec]。
 * 优先级 Shizuku > Root > Termux > App，也可显式指定通道；新增通道直接 [register]。
 *
 * ## 线程契约（H5）
 * `available()` / `detail()` 里全是真阻塞，不是纯计算：
 * - `RootChannel` 首次探测要起 `su -c id` 并 `waitFor(4s)`；
 * - `ShizukuChannel` 要 binder IPC（服务被冻结时会挂到 binder 超时）；
 * - `TermuxChannel` 要 PackageManager 跨进程查询；
 * - `AppChannel.detail()` 首次要 stat / chmod `libbusybox.so`（磁盘 IO）。
 *
 * 所以本类对外保证：
 * - [refresh] 立即返回，探测整段跑在 [probeDispatcher]（默认 `Dispatchers.IO`）上；
 * - [active] 只读探测缓存，不做 IO、不触发探测 —— 它会在 Compose 的 `combine` 变换里被调用，
 *   那里的执行线程是 `Dispatchers.Main.immediate`；
 * - [exec] 是挂起函数，解析通道 + 复查可用性也在 [probeDispatcher] 上。
 *
 * 并发契约：
 * - 探测**单飞**：[refresh] / [refreshNow] / [ensureProbed] 共用一把 [probeMutex]，
 *   同一时刻只有一轮 `available()/detail()` 在跑（叠探测 = 叠 su / binder）；
 * - 通道表是**不可变快照**：[register] 只做一次原子换引用，探测侧读快照，
 *   IO 期间不持锁，主线程不会被探测拖住；
 * - 取消穿透：通道抛 `CancellationException` 不会被当成「不可用」吞掉。
 */
class ShellBridge private constructor(
    private val context: Context?,
    private val probeDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    seedChannels: List<ShellChannel>,
) {

    /** 生产入口：四个内置通道 + 默认 IO 调度器。 */
    constructor(context: Context) : this(
        context = context,
        probeDispatcher = Dispatchers.IO,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        seedChannels = listOf(
            ShizukuChannel(),
            RootChannel(),
            TermuxChannel(context),
            AppChannel(context),
        ),
    )

    /**
     * 测试入口：通道、调度器、scope 全部注入，不碰 Android Context。
     * 走这条构造的实例没有 Context，只有 [terminalSpec] 依赖它（会直接抛）。
     */
    internal constructor(
        seedChannels: List<ShellChannel>,
        probeDispatcher: CoroutineDispatcher,
        scope: CoroutineScope,
    ) : this(null, probeDispatcher, scope, seedChannels)

    /**
     * 通道表用「不可变快照 + 原子换引用」维护：[register] 可能在主线程追加，
     * 探测侧读 [channels] 拿到的永远是一份自洽快照 —— **IO 期间不持任何锁**，
     * 既不会 `ConcurrentModificationException`，也不会因为加锁把主线程拖住。
     */
    @Volatile
    private var channels: List<ShellChannel> = seedChannels.toList()

    private val channelsLock = Any()

    private val _status = MutableStateFlow<List<ChannelStatus>>(emptyList())
    val status: StateFlow<List<ChannelStatus>> = _status.asStateFlow()

    /**
     * 探测单飞锁：所有探测路径（[refresh] / [refreshNow] / [ensureProbed]）都在它里面跑。
     * 叠探测 = 叠 `su` / binder，正是 ANR 面。
     */
    private val probeMutex = Mutex()

    /**
     * 有一次强制探测在排队。用 CAS 合并，避免主线程连点叠出多轮探测；
     * 探测进行中再点，最多再补一轮。
     */
    private val probeRequested = AtomicBoolean(false)

    val shizuku: ShizukuChannel? get() = channels.filterIsInstance<ShizukuChannel>().firstOrNull()

    fun register(channel: ShellChannel, replace: Boolean = false) {
        synchronized(channelsLock) {
            val index = channels.indexOfFirst { it.id == channel.id }
            if (index < 0) channels = channels + channel
            else if (replace) channels = channels.mapIndexed { i, current -> if (i == index) channel else current }
        }
        refresh()
    }

    fun unregister(id: String) {
        synchronized(channelsLock) {
            channels = channels.filterNot { it.id == id }
        }
        refresh()
    }

    fun all(): List<ShellChannel> = channels

    /**
     * 重新探测各通道可用性（装完 Shizuku / 授权后调用）。
     *
     * **立即返回，调用线程上不做任何 IO**。以前这里是同步的，于是
     * `AgentsViewModel.init { shell.refresh() }` 把 4 个通道的阻塞探测
     * 全压在主线程上（首次进 Agent 页即触发）。
     *
     * 强制语义保留：调用它一定会有一次**新的**探测（已有缓存不算答案）。
     * 连点由 [probeRequested] 合并成一轮；探测进行中再点，最多再补一轮。
     */
    fun refresh() {
        if (!probeRequested.compareAndSet(false, true)) return
        scope.launch { drainProbeRequests() }
    }

    /** 把排队的强制探测一轮轮跑完。每轮都在 [probeMutex] 里，永不并发。 */
    private suspend fun drainProbeRequests() {
        while (probeRequested.getAndSet(false)) {
            probeMutex.withLock { probeLocked() }
        }
    }

    /**
     * 挂起直到探测结果落地，返回这一轮结果。**强制探测**，不吃缓存。
     *
     * 逐通道 `available()` / `detail()` 全部在 [probeDispatcher] 上执行；
     * 单个通道抛异常仍降级为 `available=false` / `detail=""` —— 与旧实现的错误语义一致。
     * `CancellationException` 不降级，照常向上抛（取消 ≠ 通道不可用）。
     */
    suspend fun refreshNow(): List<ChannelStatus> = probeMutex.withLock { probeLocked() }

    /**
     * 还没有探测结果就补一次；已有结果直接返回。
     *
     * 缓存检查放在锁**内**：`init` 那次探测还在飞的时候进来，会等它落地后直接读结果，
     * 而不是自己再发一轮 —— 以前这里正是两条探测重叠的入口。
     */
    suspend fun ensureProbed(): List<ChannelStatus> = probeMutex.withLock {
        _status.value.takeIf { it.isNotEmpty() } ?: probeLocked()
    }

    /** 真正的一轮探测。调用方必须已持有 [probeMutex]。 */
    private suspend fun probeLocked(): List<ChannelStatus> {
        // 快照在切 IO **之前**取：探测期间 register 的通道落到下一轮，不会中途改变这一轮。
        val snapshot = channels
        val probed = withContext(probeDispatcher) {
            snapshot.map { c ->
                ChannelStatus(
                    id = c.id,
                    label = c.label,
                    kind = c.kind,
                    available = guarded(false) { c.available() },
                    detail = guarded("") { c.detail() },
                )
            }
        }
        _status.value = probed
        return probed
    }

    /** 单个通道探测失败降级，但取消必须穿透 —— 别把取消当成「通道不可用」。 */
    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (ignored: Throwable) {
        fallback
    }

    /**
     * 用户指定的通道（"auto" 或 null 表示自动）。设置页写这里，模型侧无需知道。
     * 指定了就只用它；它不可用时回落到自动顺序，避免模型完全没法干活。
     */
    @Volatile
    var preferred: String? = null

    /**
     * 自动顺序：Shizuku / Root 有特权排最前，其次才是**应用内** Shell。
     * Termux 放到最后：它需要用户去 Termux 里手动开 allow-external-apps，
     * 不该在应用内 Shell 明明可用的情况下抢走通道（以前报「去 Termux 授权」就是这个）。
     *
     * **只读探测缓存**：不做 IO、不触发探测。缓存为空返回 null，
     * 需要结果的调用方先 [ensureProbed]（或直接用挂起的 [exec]）。
     */
    fun active(): ShellChannel? {
        val usable = _status.value.filter { it.available }.map { it.id }
        if (usable.isEmpty()) return null
        preferred?.takeIf { it.isNotBlank() && it != "auto" }?.let { id ->
            if (id in usable) byId(id)?.let { return it }
        }
        return AUTO_ORDER.firstNotNullOfOrNull { id -> if (id in usable) byId(id) else null }
            ?: usable.firstNotNullOfOrNull { byId(it) }
    }

    fun byId(id: String): ShellChannel? = channels.firstOrNull { it.id == id }

    /** 模型侧统一入口。 */
    suspend fun exec(command: String, channelId: String? = null, timeoutMs: Long = 30_000): ShellResult {
        ensureProbed()
        return when (val resolved = withContext(probeDispatcher) { resolve(channelId) }) {
            is Resolution.Ready -> resolved.channel.exec(command, timeoutMs)
            is Resolution.Unavailable -> resolved.result
        }
    }

    // —— 可选 channel 参数 & channel_list ——

    /**
     * 把模型给的 `channel` 参数翻成注册表里的 id。
     * null / 空 / "auto" / 认不出的名字一律返回 null —— 调用方据此走旧的自动顺序，
     * 这样「不传 channel」与旧行为逐字节一致，不会被新参数意外改掉。
     */
    fun channelIdFor(channel: String?): String? {
        val name = channel?.trim()?.lowercase() ?: return null
        return when (name) {
            "", "auto", "default" -> null
            in CHANNEL_NAMES -> name
            else -> null
        }
    }

    /**
     * 带可选通道的 shell 执行。**默认路径与 [exec] 完全一致**（channel 为空 → 原样转调 [exec]）；
     * 只有显式点名时才按名字取通道，且该通道不可用就回落自动顺序 ——
     * 宁可降级到能跑的通道，也不要因为写错一个名字让模型彻底没法干活。
     *
     * 执行体放在 [probeDispatcher] 上：命令是阻塞进程，不该压在调用方（可能是主线程）上。
     */
    suspend fun execChannel(
        command: String,
        channel: String? = null,
        timeoutMs: Long = 30_000,
    ): ShellResult {
        val id = channelIdFor(channel) ?: return exec(command, null, timeoutMs)
        ensureProbed()
        val picked = withContext(probeDispatcher) {
            byId(id)?.takeIf { guarded(false) { it.available() } }
        } ?: return exec(command, null, timeoutMs)
        return withContext(probeDispatcher) { picked.exec(command, timeoutMs) }
    }

    /**
     * `channel_list()`：逐通道给出 name / available / reason，供模型自己决定用哪条，
     * 也供设置页展示。reason 走各通道的 detail()（已在 [probeLocked] 里被 guarded 包住），
     * 所以未装 Shizuku、没有 su 都只是「不可用 + 一句人话」，不会抛异常。
     */
    suspend fun channelList(): List<ChannelListing> {
        val probed = ensureProbed().associateBy { it.id }
        return CHANNEL_NAMES.map { name ->
            val status = probed[name]
            when {
                status == null -> ChannelListing(name, false, "通道未注册：当前构建没有这条通道")
                status.available -> ChannelListing(name, true, status.detail.ifBlank { "可用" })
                else -> ChannelListing(name, false, status.detail.ifBlank { "${status.label} 不可用" })
            }
        }
    }

    /** [channelList] 的 JSON 形态，模型侧直接当工具返回值用。 */
    suspend fun channelListJson(): String =
        MiniJson.arr(channelList().map { it.toJson() })

    /** 通道解析 + 可用性复查：可能起 su / binder / PackageManager，必须在 IO 上跑。 */
    private fun resolve(channelId: String?): Resolution {
        val channel = channelId?.let { byId(it) } ?: active()
            ?: return Resolution.Unavailable(ShellResult.unavailable("没有可用的执行通道"))
        val ok = guarded(false) { channel.available() }
        if (!ok) {
            val why = guarded("") { channel.detail() }
            return Resolution.Unavailable(ShellResult.unavailable("${channel.label} 不可用：$why"))
        }
        return Resolution.Ready(channel)
    }

    private sealed interface Resolution {
        data class Ready(val channel: ShellChannel) : Resolution
        data class Unavailable(val result: ShellResult) : Resolution
    }

    /**
     * 内置终端（真 PTY）的启动参数。优先内置 busybox，缺了就退系统 sh ——
     * 模拟器/其他 ABI 上 libbusybox.so 没被打包时终端仍然能用，只是命令少一些。
     *
     * 里面要 stat/chmod busybox、装 applet 软链、`mkdirs` 三个目录，同样不下主线程。
     */
    suspend fun terminalSpec(fallback: (suspend () -> ShellSpec)? = null): ShellSpec = withContext(probeDispatcher) {
        val proot = byId("proot")
        if (proot != null && guarded(false) { proot.available() }) {
            proot.terminalSpec()?.let { return@withContext it }
        }
        if (fallback != null) fallback() else hostTerminalSpec()
    }

    private suspend fun hostTerminalSpec(): ShellSpec = withContext(probeDispatcher) {
        val ctx = requireNotNull(context) { "该 ShellBridge 未绑定 Context（测试构造），不支持内置终端" }
        // 不用 runCatching：Busybox.prepare 是挂起函数，runCatching 会把取消也吞成「没有 busybox」。
        val bb: java.io.File? = try {
            Busybox.prepare(ctx)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ignored: Throwable) {
            null
        }
        val home = java.io.File(ctx.filesDir, "home").apply { mkdirs() }.absolutePath
        if (bb != null) {
            ShellSpec(
                shell = bb.absolutePath,
                args = listOf("sh"),
                cwd = home,
                env = Busybox.shellEnv(ctx),
                label = "busybox",
            )
        } else {
            ShellSpec(
                shell = "/system/bin/sh",
                args = listOf("sh"),
                cwd = home,
                env = Busybox.shellEnv(ctx),
                label = "system sh",
            )
        }
    }

    /** 供 DSH 运行时注入的工具说明（写进 system prompt / tool 列表）。 */
    fun describeForModel(): String = buildString {
        appendLine("你运行在用户 Android 手机上，可以通过 shell 工具直接操作这台设备。")
        appendLine("可用通道：")
        val probed = _status.value
        probed.forEach { s ->
            appendLine("- ${s.label}（${s.id}）：${if (s.available) "可用" else "不可用"} · ${s.detail}")
        }
        append("调用方式：shell(command) 会使用当前最优通道执行并返回 stdout。")
        appendLine()
        if (probed.isEmpty()) refresh() // 首次还没探测：异步补一次，下次生成就有内容
        val cur = active()
        if (cur == null) append("当前没有可用通道。")
        else append("当前生效通道：${cur.label}（${cur.id}）。")
    }

    private companion object {
        // proot 排第一：Linux 环境一旦激活，它就是最接近「完整 Linux」的通道。
        // 它 available() 为 false（没装/没激活）时自动落到下一条。
        val AUTO_ORDER = listOf("proot", "shizuku", "root", "app", "termux")

        /** 模型可显式指定的通道名（顺序即 channel_list 的展示顺序）。 */
        val CHANNEL_NAMES = listOf("proot", "app", "shizuku", "root")
    }
}
