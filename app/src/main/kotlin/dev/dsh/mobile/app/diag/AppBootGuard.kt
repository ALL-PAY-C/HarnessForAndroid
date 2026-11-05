package dev.dsh.mobile.app.diag

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.download.diag.LkgPlanner
import dev.dsh.mobile.core.download.diag.SettingsSnapshot
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.diag.BootState
import dev.dsh.mobile.core.model.diag.SafeMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动守卫的 Android 胶水层。
 *
 * 判定逻辑（要不要计一次失败、要不要回滚）不在这里：计数用 core:model 的 [SafeMode]，
 * 差异计算用 core:download 的 [LkgPlanner]，这里只负责「在正确的时机调用它们 + 把证据写下来」。
 *
 * 步骤 3 之后这里多做了三件事：真进安全模式、真回滚配置、真上传崩溃文件。
 */
class AppBootGuard(
    private val store: BootGuardStore,
    private val crashStore: CrashStore,
    private val scope: CoroutineScope,
    private val versionCode: Int = 0,
    /** 配置快照/回滚的读写口；null 时回滚与快照退化为只打日志（不阻断启动）。 */
    private val settings: SettingsStore? = null,
    /** 快照目录（filesDir/lkg）；null 时同样退化为只打日志。 */
    lkgDir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 首帧后要一并归零的外部计数（插件宿主的 boot.failures）；由 app 层接 pluginHost.markBootOk。 */
    private val onBootOk: () -> Unit = {},
) {

    private val safeMode = SafeMode(store, clock)

    /** 安全模式的持久标志与三个开关；粘性，只能由诊断页显式退出。 */
    val safeModeController = SafeModeController(store, clock)

    private val lkgStore: LkgStore? = lkgDir?.let { LkgStore(it) }

    private val uploader: CrashUploader? = settings?.let { CrashUploader(it) }

    @Volatile
    private var reachedUi = false

    @Volatile
    private var engineReady = false

    private var watchdogJob: Job? = null

    private var runtimeJob: Job? = null

    private var uploadJob: Job? = null

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
            // 判定为 ENTER_SAFE_MODE 就必须真的进：只打日志等于用户永远看到正常的 UI，
            // 而同一份坏配置会在下一次启动继续把进程打死。
            // 黄条是给用户看的，别把内部枚举名（ENGINE / UI）直接印上去。
            val reason = safeModeController.reason()
                ?: prevKind?.let { "上次启动崩溃（" + kindLabel(it) + "）" }
                ?: "连续启动失败"
            safeModeController.enter(reason)
            AppLog.i("SAFE_MODE enter reason=" + reason + " attempt=" + attemptCount)
        }
        watchdog()
        // 回滚与上传都放到后台：t0 的任何阻塞都会变成「冷启动慢」的用户体感，
        // 而这两件事的正确性都不依赖「首帧之前完成」。
        scope.launch { runCatching { rollbackIfNeeded() }.onFailure { AppLog.w("BOOT_ROLLBACK_ERROR " + it.message) } }
        uploadJob = scope.launch { runCatching { uploader?.uploadPending(crashStore) } }
    }

    /** 首帧：本次启动算成功，计数归零、物证清空；引擎已 Ready 时顺手落一份配置快照。 */
    fun onFirstFrame() {
        if (reachedUi) return
        reachedUi = true
        store.markReachedUi()
        store.markBootOk()
        // SafeMode.markBootOk 把 boot.attempt 写回 0：这是「本次启动成功」唯一的持久信号。
        safeMode.markBootOk()
        // 插件宿主另有一份 boot.failures（PluginStore 用它决定要不要暂停第三方插件）：
        // 这里不一起归零，它只增不减，安全模式会永久粘连在每一台机器上。
        runCatching { onBootOk() }.onFailure { AppLog.w("BOOT_OK plugin hook failed: " + it.message) }
        AppLog.i("BOOT_OK attempt=" + attemptCount + " engineReady=" + engineReady)
        snapshotIfReady()
    }

    /** 引擎到过 Ready 才允许记 last-good 配置快照。 */
    fun onEngineReady() {
        if (engineReady) return
        engineReady = true
        store.markEngineReady()
        AppLog.i("BOOT_ENGINE_READY attempt=" + attemptCount)
        snapshotIfReady()
    }

    /**
     * 订阅 RuntimeState（AppContainer.runtime）。不改 transport：这里只是消费者。
     * 只认 Ready —— NotConfigured 是「新装用户还没配模型」，不算引擎成功，也不写快照。
     */
    fun bindRuntime(runtime: StateFlow<RuntimeState>) {
        if (runtimeJob != null) return
        runtimeJob = scope.launch {
            runtime.collect { state ->
                if (state is RuntimeState.Ready) onEngineReady()
            }
        }
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
     * 连续失败到 [ROLLBACK_AFTER] 就把配置回滚到最近一份 LKG 快照。
     *
     * 只写差异键：整体覆盖会把用户崩溃前刚改好的无关设置一起抹掉。回滚前先存一份
     * pre-rollback 审计副本，诊断页可以「恢复上次配置」把回滚撤销。回滚后计数归 1，
     * 给用户一次观察机会；再崩一次才进安全模式。
     */
    suspend fun rollbackIfNeeded() {
        val failures = attemptCount
        val records = withContext(Dispatchers.IO) { crashStore.list() }
        val need = failures >= ROLLBACK_AFTER
        AppLog.i("BOOT_ROLLBACK_CHECK failures=$failures need=$need crashRecords=" + records.size)
        if (!need) return
        val latest = records.firstOrNull()?.let { crashStore.read(it) }
        AppLog.w(
            "BOOT_ROLLBACK_TRIGGER failures=$failures latestKind=" + (latest?.kind ?: "-") +
                " latestSig=" + (latest?.signature ?: "-"),
        )
        val settingsStore = settings
        val lkg = lkgStore
        if (settingsStore == null || lkg == null) {
            AppLog.w("BOOT_ROLLBACK_SKIPPED reason=no-lkg-store")
            return
        }
        val snapshot = lkg.latest()
        if (snapshot == null) {
            // 没有任何快照可回：第一次崩溃循环（还没成功启动过）就是这种情况，不能当成错误。
            AppLog.w("BOOT_ROLLBACK_SKIPPED reason=no-snapshot failures=$failures")
            return
        }
        val current = withContext(Dispatchers.IO) { settingsStore.snapshotEntries() }
        val plan = LkgPlanner.plan(snapshot, current)
        val auditVersion = lkg.nextVersion()
        lkg.savePreRollback(SettingsSnapshot.of(auditVersion, clock(), versionCode, current))
        if (plan.isEmpty) {
            AppLog.i("BOOT_ROLLBACK noop v$auditVersion->v" + snapshot.version + " keep=" + plan.keep.size)
            store.put(SAFE_MODE_ATTEMPT_KEY, "1")
            return
        }
        withContext(Dispatchers.IO) { settingsStore.applyEntries(plan.restore) }
        store.put(SAFE_MODE_ATTEMPT_KEY, "1")
        store.put(LAST_ROLLBACK_KEY, "v" + auditVersion + "->v" + snapshot.version)
        AppLog.i(
            "BOOT_ROLLBACK v" + auditVersion + "->v" + snapshot.version +
                " restored=" + plan.restore.size + " dropped=" + plan.dropped.size + " keep=" + plan.keep.size,
        )
    }

    /** 引擎 Ready + 首帧都到过才写快照：只有 UI 起来或只有引擎起来都不是「可用配置」。 */
    private fun snapshotIfReady() {
        if (!engineReady || !reachedUi) return
        val settingsStore = settings ?: return
        val lkg = lkgStore ?: return
        scope.launch {
            runCatching {
                val entries = withContext(Dispatchers.IO) { settingsStore.snapshotEntries() }
                lkg.writeSnapshot(entries, versionCode, clock())
            }.onFailure { AppLog.w("LKG_SNAPSHOT_ERROR " + it.message) }
        }
    }

    /** 上一次启动的裁决。规则与文档 §3.1 的 onBootStart 一致，这里只落日志不落账本结构。 */
    /** 崩溃类型的用户可读名字；未知类型原样返回，免得把信息吞掉。 */
    private fun kindLabel(kind: String): String = when (kind) {
        "OOM" -> "内存不足"
        "PLUGIN" -> "插件"
        "ENGINE" -> "引擎"
        "DATABASE" -> "数据库"
        "UI" -> "界面"
        else -> kind
    }

    private fun verdictOf(prevKind: String?, prevReachedUi: Boolean, prevStartedAt: Long, aliveMs: Long): String = when {
        prevKind != null -> "COUNTED_CRASH(" + prevKind + ")"
        prevStartedAt <= 0L -> "FIRST_BOOT"
        !prevReachedUi && aliveMs in 0..SHORT_ALIVE_MS -> "UNKNOWN_INTERRUPTION"
        prevReachedUi -> "NORMAL"
        else -> "COUNTED_CRASH(no-evidence)"
    }

    /** 最近一次回滚的版本对（诊断页展示）；没有回滚过就是 null。 */
    fun readLastRollback(): String? = store.get(LAST_ROLLBACK_KEY)

    /** SafeMode 的计数器键是私有的；读它只是为了日志与步骤 3 的回滚阈值判断。 */
    private val attemptCount: Int
        get() = store.get(SAFE_MODE_ATTEMPT_KEY)?.toIntOrNull() ?: 0

    companion object {
        const val WATCHDOG_MS = 8_000L
        const val ROLLBACK_AFTER = 2
        private const val SHORT_ALIVE_MS = 8_000L
        private const val SAFE_MODE_ATTEMPT_KEY = "boot.attempt"
        /** 最近一次回滚的版本对，诊断页展示用。 */
        const val LAST_ROLLBACK_KEY = "boot.guard.lkg.lastRollback"
    }
}
