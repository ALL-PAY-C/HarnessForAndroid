package dev.dsh.mobile.plugin.html.diagram

import android.content.Context
import dev.dsh.mobile.core.common.AppLog
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 把插件包里的离线库复制到 cacheDir/diagram-v1/，供 WebView 以 file:// 相对路径加载。
 *
 * 注意：Kotlin 块注释可以嵌套，注释正文里别写 "斜杠星" 这种序列，会把整段吞掉。
 *
 * 为什么要复制：库放在 src/main/resources（Java 资源，跟着 APK 的 zip 条目走），
 * WebView 读不到 ClassLoader 里的东西，只能先落盘再 loadDataWithBaseURL("file://<dir>/")。
 *
 * 为什么要多路兜底：宿主装载插件有两条路 —— PathClassLoader(apk) 与 InMemoryDexClassLoader(dex 字节)。
 * 后者根本没有 zip 路径，Class.getResourceAsStream 必然返回 null（实测 API 35 上就踩到了）。
 * 所以按「Java 资源 → ClassLoader 里的 APK 路径 → App 的插件 APK 目录 → 已安装包」依次找，
 * 命中一次就把策略打日志，真机排查时能直接看出走的是哪条。
 *
 * 复制幂等：先写 .tmp 再比大小，大小一致就丢掉 .tmp —— 进程中途被杀也不会留下半截 .js。
 */
internal object DiagramAssets {

    /** 目录名带版本：换库/换打包方式时改它，旧缓存自动失效，不需要清理逻辑。 */
    private const val DIR = "diagram-v1"

    /** 插件 APK 里这些库的 zip 条目前缀。 */
    private const val ENTRY_ROOT = "diagram/"

    /** 宿主的插件 APK 常驻目录（见 plugin-host 的 ExternalPlugins.APK_DIR）。 */
    private const val APK_DIR = "plugins-apk"

    @Volatile
    private var cached: File? = null

    /** 上一次命中的来源 APK：6 个库都从同一个包读，别每个文件都重扫一遍已安装包。 */
    @Volatile
    private var source: File? = null

    /** 已安装包列表也只算一次。 */
    @Volatile
    private var apkCache: List<File>? = null

    /** 首次命中的策略，只记一次，避免六次复制刷六行日志。 */
    @Volatile
    private var origin: String? = null

    // 并发进来会互相踩临时文件：A 刚写完 .tmp、B 抢到 rename、A 回头 copyTo 就 NoSuchFile。
    // 复制总共只做一次（之后 cached 命中直接返回），所以整段串行化代价可忽略。
    @Synchronized
    fun dir(context: Context): File? {
        cached?.let { if (it.isDirectory) return it }
        val root = File(context.cacheDir, DIR)
        return try {
            if (!root.isDirectory && !root.mkdirs()) {
                AppLog.w("dsh.diagram: 建不出缓存目录 " + root.absolutePath)
                return null
            }
            DiagramHtml.ASSETS.forEach { copyIfNeeded(context, root, it) }
            cached = root
            root
        } catch (t: Throwable) {
            AppLog.w("dsh.diagram: 释放离线库失败", t)
            null
        }
    }

    private fun copyIfNeeded(context: Context, root: File, name: String) {
        val target = File(root, name)
        val input = open(context, name)
            ?: throw IllegalStateException("插件包里找不到 " + ENTRY_ROOT + name)
        input.use { stream ->
            val tmp = File(root, name + ".tmp." + Thread.currentThread().id)
            tmp.outputStream().use { out -> stream.copyTo(out, 1 shl 16) }
            if (target.isFile && target.length() == tmp.length()) {
                tmp.delete()
                return
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** 按可靠性从高到低找库，返回打开好的流（调用方负责关闭）。 */
    private fun open(context: Context, name: String): InputStream? {
        // 0) 上一个库是从哪儿读到的，这个大概率同一处，先试它（扫包很贵，别做六遍）。
        source?.let { apk -> zipStream(apk, name)?.let { return it } }

        // 1) 普通 Java 资源：插件 APK 在 ClassLoader 的 dexPath 里时这条就够。
        DiagramAssets::class.java.getResourceAsStream("/" + ENTRY_ROOT + name)?.let {
            note("classpath")
            return it
        }

        // 2) 从 ClassLoader 的 DexPathList 描述里掏出插件 APK 的真实路径。
        loaderApkFiles().forEach { apk ->
            zipStream(apk, name)?.let {
                source = apk
                note("classloader " + apk.absolutePath)
                return it
            }
        }

        // 3) 宿主把导入的插件 APK 放在 filesDir/plugins-apk。
        File(context.filesDir, APK_DIR).listFiles()?.forEach { apk ->
            if (apk.isFile && apk.name.endsWith(".apk")) {
                zipStream(apk, name)?.let {
                    source = apk
                    note("filesDir " + apk.name)
                    return it
                }
            }
        }

        // 4) 最后兜底：扫已安装包（用户在系统里直接装插件 APK 的场景）。
        installedApks(context).forEach { apk ->
            zipStream(apk, name)?.let {
                source = apk
                note("installed " + apk.absolutePath)
                return it
            }
        }
        return null
    }

    /** 从每个 ClassLoader 的 toString 里抓 zip file "..." 条目，父加载器也一起看。 */
    private fun loaderApkFiles(): List<File> {
        val out = LinkedHashSet<File>()
        var loader: ClassLoader? = DiagramAssets::class.java.classLoader
        while (loader != null) {
            ZIP_PATH.findAll(loader.toString()).forEach { m ->
                runCatching { File(m.groupValues[1]) }
                    .getOrNull()
                    ?.takeIf { it.isFile }
                    ?.let { out += it }
            }
            loader = loader.parent
        }
        return out.toList()
    }

    private fun installedApks(context: Context): List<File> {
        apkCache?.let { return it }
        val list = runCatching {
            context.packageManager.getInstalledPackages(0)
                // 插件包优先：命中之后 source 会记住它，后面的库不再扫包
                .sortedBy { if (it.packageName.startsWith("dev.dsh.mobile")) 0 else 1 }
                .mapNotNull { it.applicationInfo?.sourceDir }
                .map(::File)
                .filter { it.isFile && it.name.endsWith(".apk") }
        }.getOrElse {
            AppLog.w("dsh.diagram: 列已安装包失败 " + it.message)
            emptyList()
        }
        apkCache = list
        return list
    }

    private fun zipStream(apk: File, name: String): InputStream? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(ENTRY_ROOT + name) ?: return@use null
            // ZipFile 关掉后 entry 的流还能读吗？不能 —— 读进内存再交出去。
            zip.getInputStream(entry).use { it.readBytes() }.inputStream()
        }
    }.getOrNull()

    private fun note(how: String) {
        if (origin == null) {
            origin = how
            AppLog.i("dsh.diagram: 离线库来自 " + how)
        }
    }

    private val ZIP_PATH = Regex("zip file \"([^\"]+)\"")
}
