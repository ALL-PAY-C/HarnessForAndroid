package android.util

/**
 * 纯 JVM 单测里 `android.jar` 的 [Log] 是桩，调用会抛「not mocked」。
 * 本模块单测需要驱动 `AppLog.w/d/i/e`（core/common），故用本地空实现压掉，
 * 让日志成为无副作用调用、不干扰异常断言。仅测试源集生效，不改生产。
 */
object Log {
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int = 0
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int = 0
}
