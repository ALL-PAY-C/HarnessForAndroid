package dev.dsh.mobile.noderuntime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程内 Node 运行时（Node.js v22.23.2，第三方 fork OpenFlun/nodejs-mobile 的预编译 libnode.so，
 * NODE_MODULE_VERSION 127）。
 *
 * 设计约束：
 * - node::Start 跑完整个事件循环才返回，所以它在一条独立线程（node-main）上执行；
 *   [start] 会阻塞到 node 退出并返回退出码。
 * - 一个进程只允许一个 Node 实例：第二次调用直接失败（libnode 的全局状态不可重入）。
 * - 不改全局 cwd：调用方把绝对路径写进 args，需要切目录时由 JS 侧自己 process.chdir()。
 */
object NodeRuntime {

    private const val TAG = "DshMobile"
    private const val LIB_NAME = "libnode.so"
    private const val JNI_LIB = "node_starter"

    private val busy = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded = false

    // libnode 的全局状态不可重入：node::Start 返回后同进程再调一次会崩，
    // 所以这里按"一次性"处理，而不是仅按"当前是否在跑"处理。
    @Volatile
    private var startedOnce = false

    /** 在 Application 启动时调一次，之后 [isAvailable] 才能定位 nativeLibraryDir。 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** libnode.so 是否真的随 APK 落到了 nativeLibraryDir（useLegacyPackaging 才会解出来）。 */
    fun isAvailable(): Boolean {
        val ctx = appContext ?: return false
        val dir = ctx.applicationInfo?.nativeLibraryDir ?: return false
        return File(dir, LIB_NAME).isFile
    }

    /**
     * 启动 node，阻塞到退出。
     *
     * @param args 完整 argv，argv[0] 必须是程序名（通常传 "node"）。
     * @return 退出码；已有实例在跑、libnode 缺失或加载失败时返回 failure。
     */
    fun start(args: List<String>): Result<Int> {
        if (!isAvailable()) {
            return Result.failure(IllegalStateException("libnode.so 不在 nativeLibraryDir"))
        }
        if (startedOnce) {
            return Result.failure(IllegalStateException("Node 已在本进程启动过，libnode 不支持二次启动"))
        }
        if (!busy.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("已有 Node 实例在跑"))
        }
        val argv = if (args.isEmpty()) listOf("node") else args
        try {
            if (!loaded) {
                try {
                    System.loadLibrary(JNI_LIB)
                    loaded = true
                } catch (t: Throwable) {
                    return Result.failure(t)
                }
            }
            startedOnce = true
            var code = -1
            var error: Throwable? = null
            val worker = Thread({
                try {
                    code = nativeStart(argv.toTypedArray())
                } catch (t: Throwable) {
                    error = t
                }
            }, "node-main")
            worker.start()
            worker.join()
            error?.let { return Result.failure(it) }
            return Result.success(code)
        } finally {
            busy.set(false)
        }
    }

    private external fun nativeStart(args: Array<String>): Int
}
