package dev.dsh.mobile.app.diag

import android.content.Context
import android.os.Build
import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.model.diag.CrashClassifier
import dev.dsh.mobile.core.model.diag.CrashKind
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 崩溃落盘器。只在主进程安装（:node 崩了由 binder 断开回主进程，主进程再记 ENGINE）。
 *
 * 安装点必须在任何可能抛异常的初始化之前：AppContainer 构造失败时如果 handler 还没装，
 * 那次崩溃就只剩 logcat，下次启动拿不到任何物证。
 */
class CrashReporter(
    private val appContext: Context,
    val store: CrashStore,
    private val boot: BootGuardStore,
    private val scope: CoroutineScope,
) : Thread.UncaughtExceptionHandler {

    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        AppLog.i("CRASH_REPORTER installed prev=" + previous?.javaClass?.name)
    }

    /**
     * 固定顺序：落盘 → 回填启动账本 → 清理 → 转交原 handler。
     * 写盘放最前是因为后面每一步都可能再抛；转交放最后且无条件执行，否则 ANR/tombstone/
     * 系统崩溃对话框全部消失（进程还是死，但系统不再知道它是怎么死的）。
     */
    override fun uncaughtException(t: Thread, e: Throwable) {
        val record = recordOf(t.name, e, null, null, null)
        val written = runCatching { store.write(record) }.getOrNull()
        if (written == null) AppLog.w("CRASH_WRITE_FAILED thread=" + t.name + " kind=" + record.kind)
        runCatching { boot.markCrashPending(record.kind) }
        runCatching { store.prune(MAX_FILES, MAX_AGE_DAYS) }
        AppLog.e("CRASH_UNCAUGHT " + e::class.java.name + ": " + (e.message ?: ""), e)

        val next = previous
        if (next != null) {
            runCatching { next.uncaughtException(t, e) }
        } else {
            // 没有旧 handler（理论上不会发生）：自己完成「让进程死」这件事，别静默吞掉。
            runCatching {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /**
     * 受控上报：引擎 binder 断开、插件 register/ensureStarted 失败这类「进程没死但功能没了」
     * 的现场。kind 由调用方给：分类器只看得到栈，看不出「这是引擎死了」。
     */
    fun recordCaught(kind: CrashKind, where: String, detail: Any?) {
        val error = detail as? Throwable
        val text = if (error == null) detail?.toString() else null
        val written = runCatching { store.write(recordOf(Thread.currentThread().name, error, kind, where, text)) }.getOrNull()
        if (written == null) AppLog.w("CRASH_WRITE_FAILED where=" + where)
        else AppLog.w("CRASH_RECORDED kind=" + kind + " where=" + where + " file=" + written.name)
        // 受控上报不赶时间：清理丢到 IO 线程，别占 binder 回调线程。
        scope.launch(Dispatchers.IO) { runCatching { store.prune(MAX_FILES, MAX_AGE_DAYS) } }
    }

    private fun recordOf(
        thread: String,
        error: Throwable?,
        forcedKind: CrashKind?,
        where: String?,
        textDetail: String?,
    ): CrashRecord {
        val type = error?.let { it::class.java.name } ?: ("external:" + (where ?: "unknown"))
        val rawMessage = error
            ?.let { it.message?.takeIf { m -> m.isNotBlank() } ?: it.toString() }
            ?: textDetail.orEmpty()
        val rawFrames = error?.stackTrace?.map { it.className + "." + it.methodName + "(" + (it.fileName ?: "?") + ":" + it.lineNumber + ")" }.orEmpty()
        val fingerprint = CrashClassifier.classify(thread, type, rawMessage, rawFrames)
        val log = AppLog.dump().lineSequence().filter { it.isNotBlank() }.toList().takeLast(MAX_LOG_LINES)
        return CrashRecord(
            ts = System.currentTimeMillis(),
            kind = (forcedKind ?: fingerprint.kind).name,
            thread = thread,
            throwable = Redact.of(type),
            message = Redact.of(rawMessage).take(MAX_MESSAGE_CHARS),
            frames = rawFrames.map { Redact.of(it) }.take(MAX_FRAMES),
            appLog = log.map { Redact.of(it).take(MAX_LOG_LINE_CHARS) },
            process = processName(),
            bootAttempt = boot.bootAttemptId(),
            appVersion = appVersion(),
            // ponytail: Room 只有开库才知道版本号，崩溃现场不可能去开库；这里固定 0，
            // 需要真版本时由 :data 在启动成功后把 schemaVersion 写进 BootGuardStore。
            dbVersion = DB_VERSION_UNKNOWN,
            device = device(),
            signature = signatureOf(fingerprint.key),
        )
    }

    /** 脱敏在写盘前做：日志一旦落盘再脱就晚了（导出/上传都会带出去）。 */
    private object Redact {
        private val SK = Regex("""sk-[A-Za-z0-9_\-]{6,}""")
        private val BEARER = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=\-]{6,}""")
        private val SECRET_KV = Regex("""(?i)(token|api[_-]?key|password|secret|authorization|cookie)\s*[=:]\s*\S+""")
        private val PHONE = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
        private val ABS_PATH = Regex("""/(?:data|storage|sdcard|system|vendor|mnt|proc)/[^\s:,()\[\]"]*""")

        fun of(s: String): String = s
            .replace(SK, "sk-\u2022\u2022\u2022")
            .replace(BEARER, "Bearer \u2022\u2022\u2022")
            .replace(SECRET_KV) { m -> m.groupValues[1] + "=\u2022\u2022\u2022" }
            .replace(PHONE, "1\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022")
            .replace(ABS_PATH, "<path>")
    }

    private fun signatureOf(key: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        digest.take(4).joinToString("") { b -> "%02x".format(b) }
    }.getOrDefault("")

    private fun device(): String =
        Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT +
            " abi=" + (Build.SUPPORTED_ABIS.firstOrNull() ?: "?")

    @Suppress("DEPRECATION")
    private fun appVersion(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        (info.versionName ?: "?") + "(" + code + ")"
    }.getOrDefault("?")

    private fun processName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else appContext.packageName
    }.getOrDefault(appContext.packageName)

    companion object {
        const val MAX_FILES = 20
        const val MAX_AGE_DAYS = 14
        private const val DB_VERSION_UNKNOWN = 0
        private const val MAX_MESSAGE_CHARS = 2_000
        private const val MAX_FRAMES = 32
        private const val MAX_LOG_LINES = 200
        private const val MAX_LOG_LINE_CHARS = 500

        /** 装配入口：DshApplication 只需要一行。 */
        fun install(context: Context, boot: BootGuardStore, scope: CoroutineScope): CrashReporter =
            CrashReporter(context.applicationContext, CrashStore(java.io.File(context.filesDir, "crash")), boot, scope)
                .also { it.install() }
    }
}
