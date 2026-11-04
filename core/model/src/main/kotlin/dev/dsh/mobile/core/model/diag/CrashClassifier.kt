package dev.dsh.mobile.core.model.diag

/**
 * 崩溃大类。后续上报/去重都按这个维度聚合，所以宁粗不细：分得太细会让「同一问题」被拆成多条。
 */
enum class CrashKind { OOM, PLUGIN, ENGINE, DATABASE, UI, UNKNOWN }

/**
 * frames 只带前若干帧：栈尾部是框架递归与 GC 噪音，对定位与去重都没有信息量。
 */
data class CrashFingerprint(
    val kind: CrashKind,
    val key: String,
    val frames: List<String>,
)

/**
 * 纯函数分类器：不碰 IO、不碰 Android，输入就是崩溃现场的四元组，便于单测与在 :node 进程复用。
 */
object CrashClassifier {

    private const val MAX_FRAMES = 16

    private val OOM_TYPES = listOf("outofmemoryerror", "outofmemory", "oomerror")
    private val OOM_WORDS = Regex("\\boom\\b|out of memory|failed to allocate")
    private val PLUGIN_FRAMES = listOf("pluginhost", "dev.dsh.mobile.plugin")
    private val ENGINE_FRAMES = listOf(
        "nodeengine", "remotenodeengine", "nodeengineservice", "engineprocess", "libnode", "nodejs",
    )
    private val DATABASE_TYPES = listOf("sqliteexception", "sqlitefull", "databasecorruption", "databaseexception")
    private val DATABASE_FRAMES = listOf("android.database", "androidx.room", "sqlite", "core.data.db")

    // 框架帧不参与 key：同一个业务崩溃在不同 ROM / SDK 版本上框架帧会变，带上就会漏去重。
    private val FRAMEWORK_PREFIXES = listOf(
        "java.", "javax.", "jdk.", "sun.", "android.", "dalvik.", "kotlin.", "kotlinx.coroutines.", "com.android.",
    )
    private val LINE_NO = Regex(":\\d+(\\))?$")

    fun classify(
        thread: String,
        throwableType: String,
        message: String?,
        stack: List<String>,
    ): CrashFingerprint {
        val frames = stack.take(MAX_FRAMES)
        val type = throwableType.lowercase()
        val text = (throwableType + " " + (message ?: "")).lowercase()
        val frameText = frames.joinToString("\n").lowercase()

        // 顺序敏感：OOM 现场常常同时命中插件/引擎帧，但根因是内存，先判 OOM 才能得到可行动的结论。
        val kind = when {
            OOM_TYPES.any { type.contains(it) } || OOM_WORDS.containsMatchIn(text) -> CrashKind.OOM
            PLUGIN_FRAMES.any { frameText.contains(it) } -> CrashKind.PLUGIN
            ENGINE_FRAMES.any { frameText.contains(it) } -> CrashKind.ENGINE
            DATABASE_TYPES.any { type.contains(it) } || DATABASE_FRAMES.any { frameText.contains(it) } -> CrashKind.DATABASE
            thread.equals("main", ignoreCase = true) -> CrashKind.UI
            else -> CrashKind.UNKNOWN
        }
        return CrashFingerprint(kind, keyOf(throwableType, frames), frames)
    }

    /** key = 异常类型 + 首个业务帧（去行号）：行号随版本变动，类型+帧才是稳定的同一性问题。 */
    fun keyOf(throwableType: String, stack: List<String>): String {
        val business = stack
            .asSequence()
            .map { it.trim().removePrefix("at ").trim() }
            .firstOrNull { frame -> FRAMEWORK_PREFIXES.none { frame.startsWith(it) } }
            ?: stack.firstOrNull().orEmpty()
        // 去行号但保留右括号：括号是帧签名的边界，丢了会让 key 看起来像被截断的类名。
        return throwableType + "@" + LINE_NO.replace(business) { if (it.groupValues[1].isEmpty()) "" else ")" }
    }
}
