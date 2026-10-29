package dev.dsh.mobile.pluginhost

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.DexFile
import dalvik.system.InMemoryDexClassLoader
import dalvik.system.PathClassLoader
import java.nio.ByteBuffer
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.pluginapi.DshPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一个插件包登记。
 *
 * - 旧分发格式（.apk）：[apkPath] 指向 filesDir/plugins-apk 下的整包，[entryClass] 是它的
 *   DshPlugin 实现，由 [ExternalPluginStore.load] 自己装载。
 * - 新分发格式（zip）：[apkPath] 留空，[dirPath] 指向解包后的落盘目录（package.json 与入口都在
 *   里面），交给 [PluginInstaller.loadablePlugins] 装配，[ExternalPluginStore.load] 直接跳过。
 */
data class ExternalPluginEntry(
    val apkPath: String,
    val pluginId: String,
    val name: String,
    val version: String,
    val entryClass: String,
    val dirPath: String = "",
)

/**
 * 一次加载尝试的结果，给「插件管理」页做诊断用。
 * 用户装了插件却在列表里看不到时，这里能直接说出是哪一步断的。
 */
data class PluginProbe(
    val id: String,
    val name: String,
    /** file = App 内导入的 APK；system = 系统里已安装的插件 App */
    val source: String,
    val ok: Boolean,
    val error: String? = null,
)

/**
 * 外挂 APK 插件的安装 / 列举 / 卸载 / 加载。
 *
 * 两个入口都要认：
 * 1. **App 内导入**（插件市场 → 从文件安装）：APK 复制到 filesDir/plugins-apk，
 *    用 PathClassLoader(apkPath, context.classLoader) 加载。
 * 2. **系统里已安装的插件 App**：用户在文件管理器里点了 APK 安装（没有 launcher
 *    activity，装完看不到图标），这种包不会进 filesDir，必须用
 *    PackageManager 扫出来、再用 createPackageContext(INCLUDE_CODE).classLoader 加载。
 *    以前只认第 1 种，所以「装上了但插件管理里没有」。
 *
 * parent 必须是宿主的 ClassLoader：插件里 dev.dsh.mobile.pluginapi.* 的符号要解析到
 * 宿主那一份 —— 插件用 compileOnly 依赖 plugin-api，自己不带这些类。
 *
 * 任何单个插件的失败都只跳过它自己并记进 [probes]，绝不抛到调用方。
 */
class ExternalPluginStore(
    private val context: Context,
    /** zip 插件包的解包 + 装配走它；为 null 时只支持旧 .apk 整包。 */
    private val installer: PluginInstaller? = null,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dsh.ext.plugins", Context.MODE_PRIVATE)

    private val apkDir: File
        get() = File(context.filesDir, APK_DIR).also { if (!it.exists()) it.mkdirs() }

    private val _probes = MutableStateFlow<List<PluginProbe>>(emptyList())

    /** 最近一次 [load] 的逐条结果（含系统插件），供诊断 UI 订阅。 */
    val probes: StateFlow<List<PluginProbe>> = _probes.asStateFlow()

    /**
     * 装一个插件包：复制到 filesDir/plugins-apk/<id>.apk 并写进 SharedPreferences。
     *
     * 包信息优先取 APK 内 assets/plugin.json（离线、不依赖 PackageManager 能否解析），
     * 读不到再退回 PackageManager.getPackageArchiveInfo。
     */
    fun installPackage(src: File): Result<ExternalPluginEntry> = runCatching {
        require(src.isFile) { "插件包不存在：" + src.absolutePath }
        // 按内容判型（后缀不可信）：老 .apk（整包 dex）走旧路；其余 zip/tgz 当数据插件包解包；
        // 两样都不是就直说选错了文件，别让用户吃一个 ZipException 堆栈。
        when (PluginImport.classify(src)) {
            ImportKind.Apk -> installLegacyApk(src).getOrThrow()
            ImportKind.PluginPackage -> installArchive(src)
            ImportKind.Garbage -> error(PluginImport.GARBAGE_MESSAGE + src.name)
        }
    }

    /** 旧分发格式（.apk 整包）的安装：行为与以前完全一致，老包不变砖。 */
    fun installApk(src: File): Result<ExternalPluginEntry> = installPackage(src)

    private fun installLegacyApk(src: File): Result<ExternalPluginEntry> = runCatching {
        require(src.isFile) { "apk not found: " + src.absolutePath }
        // 目录平时是只读的（见 lockDown），写入前先放开。
        unlockDir()

        val meta = readPluginJson(src)
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(src.absolutePath, 0)
        }.getOrNull()

        val id = meta?.optString("id")?.takeIf { it.isNotBlank() }
            ?: archive?.packageName
            ?: error("plugin id not found in " + src.name)

        val entry = ExternalPluginEntry(
            apkPath = File(apkDir, id + ".apk").absolutePath,
            pluginId = id,
            name = meta?.optString("name")?.takeIf { it.isNotBlank() }
                ?: archive?.applicationInfo?.nonLocalizedLabel?.toString()
                ?: id,
            version = meta?.optString("version")?.takeIf { it.isNotBlank() }
                ?: archive?.versionName
                ?: "0",
            entryClass = meta?.optString("entry")?.takeIf { it.isNotBlank() } ?: DEFAULT_ENTRY,
        )

        val target = File(entry.apkPath)
        if (runCatching { target.canonicalPath != src.canonicalPath }.getOrDefault(true)) {
            src.copyTo(target, overwrite = true)
        }

        writeAll(list().filterNot { it.pluginId == id } + entry)
        // 重装即恢复：先摘掉「已移除」，否则 load() 仍然把它过滤掉。
        unblock(id)
        lockDown()
        AppLog.i("ext plugin installed: " + id + " -> " + entry.apkPath)
        entry
    }

    /**
     * 新分发格式（zip）的安装：交给 [PluginInstaller] 解包并激活，这里只留一条登记。
     * 解包目录归 PluginInstaller 管（含 package.json 与入口），卸载也由它删，本仓不碰那些文件。
     */
    private fun installArchive(src: File): ExternalPluginEntry {
        val api = installer ?: error("插件安装器未接入，装不了 zip 插件包：" + src.name)
        val manifest = runBlocking { api.installArchive(src) }.getOrElse { throw it }
        val dir = api.dirOf(manifest.id)
        val entry = ExternalPluginEntry(
            apkPath = "",
            pluginId = manifest.id,
            name = manifest.name.ifBlank { manifest.id },
            version = manifest.version.ifBlank { "0" },
            entryClass = PluginPackageFormat.entryOf(dir).orEmpty(),
            dirPath = dir.absolutePath,
        )
        // 只换同名条目：装一个 JS 插件不能顺手清掉别的插件（尤其用户自己装的 APK 插件）。
        writeAll(list().filterNot { it.pluginId == entry.pluginId } + entry)
        unblock(entry.pluginId)
        AppLog.i("ext plugin installed: " + entry.pluginId + " -> " + entry.dirPath)
        return entry
    }

    /** 已安装条目，按写入顺序。坏数据整体降级为空列表，不抛。 */
    fun list(): List<ExternalPluginEntry> = runCatching {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val apk = o.optString("apkPath")
            val dir = o.optString("dirPath")
            val id = o.optString("pluginId")
            // 两种形态二选一：整包 APK 有 apkPath，zip 插件有 dirPath。
            if (id.isBlank() || (apk.isBlank() && dir.isBlank())) return@mapNotNull null
            ExternalPluginEntry(
                apkPath = apk,
                pluginId = id,
                name = o.optString("name", id),
                version = o.optString("version", "0"),
                entryClass = o.optString("entryClass", DEFAULT_ENTRY),
                dirPath = dir,
            )
        }
    }.getOrElse {
        AppLog.w("ext plugin list unreadable", it)
        emptyList()
    }

    /**
     * 摘掉本仓登记的同名条目：只动 [id] 这一条，别的插件一根汗毛都不碰。
     *
     * 以前无条件 writeAll + block(id)：卸载一个和系统 APK 插件同 id 的 JS 插件时，会把这个 id
     * 永久写进「已移除」，那个 APK 插件从此再也加载不起来。现在：
     *  · 本仓没有同名条目 → 直接返回，不写盘、不拉黑；
     *  · zip 条目（dirPath 非空）→ 只摘登记，目录归 PluginInstaller 删；
     *  · 整包 APK 条目 → 删文件并拉黑，压住用户自己从文件管理器装过的同 id APK 被扫回来。
     */
    fun remove(id: String) {
        val entries = list()
        val gone = entries.firstOrNull { it.pluginId == id }
        if (gone == null) {
            AppLog.i("ext plugin remove skipped (not owned by store): " + id)
            return
        }
        writeAll(entries.filterNot { it.pluginId == id })
        if (gone.apkPath.isNotBlank()) {
            unlockDir()
            runCatching { File(gone.apkPath).apply { setWritable(true, true) }.delete() }
            lockDown()
            block(id)
        }
        AppLog.i("ext plugin removed: " + id)
    }

    /** 用户主动移除过的插件 id：load() 的两个来源都按它过滤。 */
    fun blocked(): Set<String> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_BLOCKED, "[]").orEmpty())
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toSet()
    }.getOrElse {
        AppLog.w("ext plugin blocked list unreadable", it)
        emptySet()
    }

    private fun block(id: String) {
        writeBlocked(blocked() + id)
    }

    /** 重装同一个插件即恢复：installApk 成功后调它。 */
    fun unblock(id: String) {
        val current = blocked()
        if (id !in current) return
        writeBlocked(current - id)
        AppLog.i("ext plugin unblocked: " + id)
    }

    private fun writeBlocked(ids: Set<String>) {
        prefs.edit().putString(KEY_BLOCKED, JSONArray(ids.toList()).toString()).apply()
    }

    /**
     * 加载全部可用的外挂插件：App 内导入的 + 系统里已安装的插件 App，按 manifest id 去重。
     * 失败的单条只记 AppLog.w 并跳过，结果写进 [probes]。
     */
    fun load(): List<DshPlugin> {
        // 老版本装下的 APK 是可写的，先收成只读，否则 ART 拒绝加载（见 lockDown）。
        lockDown()
        AppLog.i("ext plugin load: files=" + list().size + " system=" + installedPluginPackages().size)
        val out = LinkedHashMap<String, DshPlugin>()
        val reports = ArrayList<PluginProbe>()
        // 被用户移除过的 id 先过滤：系统里装着的插件 APK 也一样，否则删完下一次 load() 又回来。
        val blockedIds = blocked()

        for (entry in list()) {
            if (entry.dirPath.isNotBlank()) {
                // zip 插件包由 PluginInstaller.loadablePlugins() 装配（它才认得 package.json 入口），
                // 这里不能按 APK 整包去装载，否则必然失败并污染 probes。
                AppLog.i("ext plugin skipped (zip package): " + entry.pluginId)
                continue
            }
            if (entry.pluginId in blockedIds) {
                AppLog.i("ext plugin skipped (blocked): " + entry.pluginId)
                continue
            }
            val loaded = runCatching {
                instantiate(File(entry.apkPath), entry.entryClass, context.classLoader)
            }
            val plugin = loaded.getOrNull()
            if (plugin != null) {
                out.putIfAbsent(plugin.manifest.id, plugin)
                reports += PluginProbe(plugin.manifest.id, plugin.manifest.name, "file", true)
            } else {
                val err = loaded.exceptionOrNull()
                AppLog.w("ext plugin " + entry.pluginId + " load failed: " + err?.message, err)
                reports += PluginProbe(entry.pluginId, entry.name, "file", false, err?.message ?: "未知错误")
            }
        }

        for (pkg in installedPluginPackages()) {
            if (pkg in blockedIds) {
                AppLog.i("system plugin skipped (blocked): " + pkg)
                continue
            }
            val loaded = runCatching { loadInstalled(pkg) }
            val plugin = loaded.getOrNull()
            if (plugin != null && plugin.manifest.id in blockedIds) {
                AppLog.i("system plugin skipped (blocked id): " + plugin.manifest.id)
                continue
            }
            if (plugin != null) {
                // App 内导入的那份优先：同 id 只保留先加载到的。
                if (out.putIfAbsent(plugin.manifest.id, plugin) == null) {
                    reports += PluginProbe(plugin.manifest.id, plugin.manifest.name, "system", true)
                }
            } else {
                val err = loaded.exceptionOrNull()
                AppLog.w("system plugin " + pkg + " load failed: " + err?.message, err)
                reports += PluginProbe(pkg, pkg, "system", false, err?.message ?: "未知错误")
            }
        }

        _probes.value = reports
        return out.values.toList()
    }

    /** 系统里那些「没有图标」的插件 App：靠 meta-data dsh.plugin 或包名前缀认。 */
    fun installedPluginPackages(): List<String> = runCatching {
        context.packageManager
            .getInstalledPackages(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { info ->
                val app = info.applicationInfo ?: return@filter false
                app.metaData?.getBoolean(META_PLUGIN) == true ||
                    info.packageName.startsWith(SYSTEM_PLUGIN_PREFIX)
            }
            .map { it.packageName }
            .distinct()
            .toList()
    }.getOrElse {
        AppLog.w("scan installed plugin packages failed: " + it.message, it)
        emptyList()
    }

    /**
     * 系统里装好的插件包。
     *
     * 不要走 createPackageContext(CONTEXT_INCLUDE_CODE) + pluginContext.classLoader：
     * 新版 Android 上那个 ClassLoader 拿不到对方 dex，Class.forName 直接 ClassNotFoundException
     * （实测 API 35：system=2 全部 load failed）。统一用 [instantiate] 走
     * PathClassLoader(sourceDir) + 内存加载兜底，与 App 内导入的插件同一条已验证路径。
     */
    private fun loadInstalled(packageName: String): DshPlugin {
        val info = context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA,
        )
        val apkPath = info.sourceDir ?: error("拿不到插件 APK 路径：" + packageName)
        val apk = File(apkPath)
        val entryClass = readPluginJson(apk)?.optString("entry")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ENTRY
        return instantiate(apk, entryClass, context.classLoader)
    }

    /**
     * 从 APK 建 ClassLoader 并实例化入口类。
     *
     * Android 10 起 ART 拒绝从「可写」位置加载 dex：
     *   Writable dex file '/data/.../dsh.html.apk' is not allowed.
     * 正常路径靠 [lockDown] 把 APK 目录与文件收成只读；万一机型/文件系统不认 chmod，
     * 退到 [inMemoryLoader]（classes*.dex 读进内存加载，绕开文件可写检查）。
     */
    private fun instantiate(apk: File, entryClass: String, parent: ClassLoader): DshPlugin {
        require(apk.isFile) { "APK 不存在：" + apk.absolutePath }
        val fileAttempt = runCatching {
            val loader = PathClassLoader(apk.absolutePath, parent) as ClassLoader
            newInstance(loader, entryClass)
        }
        fileAttempt.getOrNull()?.let { return it }

        val memAttempt = runCatching {
            newInstance(inMemoryLoader(apk, parent), entryClass)
        }
        return memAttempt.getOrElse { throw fileAttempt.exceptionOrNull() ?: it }
    }

    private fun newInstance(loader: ClassLoader, entryClass: String): DshPlugin {
        val name = runCatching { Class.forName(entryClass, true, loader).name }
            .getOrElse { discoverEntry(loader) ?: throw it }
        val instance = Class.forName(name, true, loader).getDeclaredConstructor().newInstance()
        return instance as? DshPlugin ?: error(entryClass + " 没有实现 DshPlugin")
    }

    /** 把 APK 里的 classes*.dex 读进内存加载（不落可写目录）。 */
    private fun inMemoryLoader(apk: File, parent: ClassLoader): ClassLoader {
        val buffers = ArrayList<ByteBuffer>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                .sortedBy { it.name }
                .forEach { entry ->
                    buffers += ByteBuffer.wrap(zip.getInputStream(entry).use { it.readBytes() })
                }
        }
        require(buffers.isNotEmpty()) { "APK 里没有 classes.dex：" + apk.name }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && buffers.size > 1) {
            InMemoryDexClassLoader(buffers.toTypedArray(), parent)
        } else {
            InMemoryDexClassLoader(buffers.first(), parent)
        }
    }

    /**
     * 把插件目录与 APK 收成只读：ART 只允许从「不可写」位置加载 dex。
     * 目录本身也不能可写，否则报 Writable dex directory。
     */
    private fun lockDown() {
        runCatching {
            apkDir.listFiles()?.forEach { file ->
                file.setReadable(true, false)
                file.setWritable(false, false)
            }
            apkDir.setReadable(true, false)
            apkDir.setWritable(false, false)
        }.onFailure { AppLog.w("lock plugin dir failed: " + it.message) }
    }

    private fun unlockDir() {
        runCatching {
            apkDir.setWritable(true, true)
            apkDir.setReadable(true, true)
            apkDir.listFiles()?.forEach { it.setWritable(true, true) }
        }.onFailure { AppLog.w("unlock plugin dir failed: " + it.message) }
    }

    /**
     * 兜底：manifest 里的 entry 写错/缺失时，扫 dex 找一个 DshPlugin 实现。
     * 只在失败路径跑，且封顶 [MAX_SCAN] 个类，不会拖慢正常启动。
     */
    private fun discoverEntry(loader: ClassLoader): String? {
        val pluginType = DshPlugin::class.java
        val candidates = ArrayList<String>()
        for (apk in apkDir.listFiles { f -> PluginPackageFormat.isArchiveName(f.name) }.orEmpty()) {
            val dex = runCatching { DexFile(apk.absolutePath) }.getOrNull() ?: continue
            try {
                dex.entries().asSequence()
                    .filter { it.endsWith("Plugin") }
                    .take(MAX_SCAN)
                    .forEach { candidates += it }
            } catch (t: Throwable) {
                AppLog.w("dex scan failed: " + apk.name + " " + t.message)
            } finally {
                runCatching { dex.close() }
            }
        }
        for (name in candidates) {
            val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            if (pluginType.isAssignableFrom(cls) && !cls.isInterface) return cls.name
        }
        return null
    }

    private fun writeAll(entries: List<ExternalPluginEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("apkPath", e.apkPath)
                    .put("pluginId", e.pluginId)
                    .put("name", e.name)
                    .put("version", e.version)
                    .put("entryClass", e.entryClass)
                    .put("dirPath", e.dirPath)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** 直接读 zip 条目：不需要先加载插件的代码就能拿到 entry/id/name/version。 */
    private fun readPluginJson(apk: File): JSONObject? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("assets/plugin.json") ?: return@use null
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(text)
        }
    }.onFailure {
        AppLog.w("ext plugin " + apk.name + " has no readable assets/plugin.json", it)
    }.getOrNull()

    companion object {
        /** 没有 assets/plugin.json 时的固定类名约定。 */
        const val DEFAULT_ENTRY: String = "dev.dsh.mobile.plugin.html.HtmlPlugin"

        /** 插件 App 在 AndroidManifest 里声明的标记：<meta-data android:name="dsh.plugin" .../> */
        const val META_PLUGIN: String = "dsh.plugin"

        /** 没写 meta-data 时的兜底识别前缀。 */
        const val SYSTEM_PLUGIN_PREFIX: String = "dev.dsh.mobile.plugin."

        private const val APK_DIR = "plugins-apk"
        private const val KEY = "dsh_ext_plugins"

        /** 用户主动移除过的插件 id（JSON 数组字符串）。 */
        private const val KEY_BLOCKED = "dsh_ext_plugins_blocked"
        private const val MAX_SCAN = 2000
    }
}
