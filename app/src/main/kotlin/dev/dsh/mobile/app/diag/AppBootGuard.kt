package dev.dsh.mobile.app.diag

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.diag.BootState
import dev.dsh.mobile.core.model.diag.SafeMode
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动守卫的 Android 胶水层。
 *
 * 判定逻辑（要不要计一次失败、要不要回滚）不在这里：计数用 core:model 的 [SafeMode]，
 * 这里只负责「在正确的时机调用它 + 把证据写下来」。真正回滚在步骤 3。
 */
class AppBootGuard(
    private val store: BootGuardStore,
    private val crashStore: CrashStore,
    private val scope: CoroutineScope,
    private val versionCode: Int = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val safeMode = SafeMode(store, clock)

    @Volatile
    private var reachedUi = false

    @Volatile
    private var engineReady = false

    private var watchdogJob: Job? = null

    /**
     * t0：读上一次启动怎么结束的 → 记裁决 → 写新 pending → 起看门狗。
     * 读必须先于写：pending 一覆盖，上一次的物证就没了。
     */
    fun onApplicationCreate() {
        val prevKind = store.crashKind()
        val prevReachedUi = store.reachedUi()
        val prevStartedAt = store.startedAt()
        val aliveMs = if (prevStartedAt > 0) clock() - prevStartedAt else -1L
        val state = safeMode.beginBoot()
        val verdict = verdictOf(prevKind, prevReachedUi, prevStartedAt, aliveMs)
        val attemptId = UUID.randomUUID().toString()
        store.markBootPending(attemptId, clock(), versionCode)
        AppLog.i("BOOT_START verdict=$verdict prevAliveMs=$aliveMs attempt=$attemptId versionCode=$versionCode")
        if (state == BootState.SafeMode) {
            AppLog.w("SAFE_MODE_ENTER attempt=" + attemptCount + " reason=" + (prevKind ?: "repeated-boot-failures"))
        }
        watchdog()
    }

    /** 首帧 + 3s（AppNav 调用）：本次启动算成功，计数归零、物证清空。 */
    fun onFirstFrame() {
        if (reachedUi) return
        reachedUi = true
        store.markReachedUi()
        store.markBootOk()
        // SafeMode.markBootOk 把 boot.attempt 写回 0：这是「本次启动成功」唯一的持久信号。
        safeMode.markBootOk()
        AppLog.i("BOOT_OK attempt=" + attemptCount + " engineReady=" + engineReady)
    }

    /** 引擎到过 Ready 才允许记 last-good 配置快照（步骤 3 消费）。 */
    fun onEngineReady() {
        engineReady = true
        store.markEngineReady()
        AppLog.i("BOOT_ENGINE_READY attempt=" + attemptCount)
    }

    /**
     * t0+8s：还没首帧就只记一条日志。
     * 不写崩溃记录 —— 低端机冷启动超 8s、系统正在做 dexopt 都会命中这里，
     * 把它们写成崩溃会把「同签名连续 N 次」直接推到安全模式。真崩了有 CrashReporter 的物证。
     */
    fun watchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(WATCHDOG_MS)
            if (reachedUi) return@launch
            AppLog.w(
                "BOOT_WATCHDOG no first frame in " + WATCHDOG_MS + "ms attempt=" + attemptCount +
                    " engineReady=" + engineReady + " pending=" + store.bootAttemptId(),
            )
        }
    }

    /**
     * 只做判定 + 记账，不动配置：连续失败到 [ROLLBACK_AFTER] 就打一条待回滚日志，
     * 真正的「取上一份快照并写回差异键」在步骤 3。
     */
    suspend fun rollbackIfNeeded() {
        val failures = attemptCount
        val records = withContext(Dispatchers.IO) { crashStore.list() }
        val need = failures >= ROLLBACK_AFTER
        AppLog.i("BOOT_ROLLBACK_CHECK failures=$failures need=$need crashRecords=" + records.size)
        if (need) {
            val latest = records.firstOrNull()?.let { crashStore.read(it) }
            AppLog.w(
                "BOOT_ROLLBACK_PENDING failures=$failures latestKind=" + (latest?.kind ?: "-") +
                    " latestSig=" + (latest?.signature ?: "-") + " (回滚在步骤 3 执行)",
            )
        }
    }

    /** 上一次启动的裁决。规则与文档 §3.1 的 onBootStart 一致，这里只落日志不落账本结构。 */
    private fun verdictOf(prevKind: String?, prevReachedUi: Boolean, prevStartedAt: Long, aliveMs: Long): String = when {
        prevKind != null -> "COUNTED_CRASH(" + prevKind + ")"
        prevStartedAt <= 0L -> "FIRST_BOOT"
        !prevReachedUi && aliveMs in 0..SHORT_ALIVE_MS -> "UNKNOWN_INTERRUPTION"
        prevReachedUi -> "NORMAL"
        else -> "COUNTED_CRASH(no-evidence)"
    }

    /** SafeMode 的计数器键是私有的；读它只是为了日志与步骤 3 的回滚阈值判断。 */
    private val attemptCount: Int
        get() = store.get(SAFE_MODE_ATTEMPT_KEY)?.toIntOrNull() ?: 0

    companion object {
        const val WATCHDOG_MS = 8_000L
        const val ROLLBACK_AFTER = 2
        private const val SHORT_ALIVE_MS = 8_000L
        private const val SAFE_MODE_ATTEMPT_KEY = "boot.attempt"
    }
}
