package dev.dsh.mobile.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.WrappedException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在 App 进程里真跑已安装插件包自带的 JS 入口。
 *
 * 在此之前 PluginInstaller 只读 plugin.json / package.json，装的 npm 包一个字节
 * 的代码都不会执行 —— 界面里只有一张信息卡。这里补上执行这一半：找到入口文件、
 * 用 Rhino 解释执行、把脚本调用的 dsh 宿主 API 收集成 [JsRunResult]，
 * 再由 [JsBackedPlugin] 接进 PluginRegistry。
 *
 * 边界是硬的：
 *  - 脚本只能看到 dsh / ctx 这两个白名单对象和 JS 内建对象，
 *    没有文件、进程、反射、Packages —— 见 [BudgetFactory.makeContext] 里的
 *    initSafeStandardObjects + ClassShutter。
 *  - 任何异常都只让这一个插件失败，返回 Result.failure([JsPluginError])，绝不外抛。
 *  - 死循环靠指令计数兜底（instructionObserverThreshold，只在解释模式下生效）。
 *
 * 本类刻意不 import 任何 android.* / Compose，才能在 JVM 单测里直接跑。
 */
class JsPluginRuntime(
    private val config: Map<String, String> = emptyMap(),
    private val onSetConfig: (key: String, value: String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
) {

    /**
     * 执行 [dir] 里的 JS 入口。[pluginId] 成为脚本里 dsh.ctx.pluginId 的值。
     *
     * 失败带阶段：解析入口 / 编译 / 执行 / host api，调用方拿得到到底卡在哪一步。
     */
    fun execute(dir: File, pluginId: String): Result<JsRunResult> {
        val entry = resolveEntry(dir)
            ?: return Result.failure(JsPluginError(STAGE_ENTRY, "包内没有 JS 入口"))
        log("插件 " + pluginId + " 入口解析：" + entry.source + " -> " + entry.path)

        val entryFile = File(dir, entry.path)
        if (!entryFile.isFile) {
            return Result.failure(JsPluginError(STAGE_ENTRY, "入口文件读取失败：" + entry.path))
        }

        // 预检：先把「本端根本没有的外部模块」一次列清。真实桌面插件（cordis bundle）
        // 十有八九要 node:fs / @deepseek-ai/*，等撞上第一个 import 才报错会让人以为补一个就行。
        val missing = ExternalModules.scan(dir, entryFile)
        if (missing.isNotEmpty()) {
            return Result.failure(
                JsPluginError(
                    STAGE_LOAD,
                    ExternalModules.describe(missing),
                ),
            )
        }

        val factory = BudgetFactory()
        val cx = factory.enterContext()
        var loader: ModuleLoader? = null
        try {
            val scope = cx.initSafeStandardObjects()
            val collector = Collector(pluginId, entry.path, config, onSetConfig)
            // 先装宿主对象再执行：脚本一进来就要能看见 dsh/ctx。
            collector.install(scope, cx)

            // 入口也走模块加载器：ESM 变换、__require、相对路径解析在这里统一。
            val modules = ModuleLoader(cx, scope, dir, log)
            loader = modules
            try {
                modules.load(entryFile)
            } catch (t: Throwable) {
                // __require 抛的是 RuntimeException（Rhino 不接 checked 异常），
                // 真正的 JsPluginError 暂存在 loader 里，这里优先取它。
                modules.failure?.let { return Result.failure(it) }
                val root = rootCause(t)
                if (root is JsPluginError) return Result.failure(root)
                return Result.failure(JsPluginError(STAGE_EXEC, entry.path + " 执行失败：" + root.message, root))
            }
            val result = collector.resultOrFailure().getOrElse { return Result.failure(it) }
            log("插件 " + pluginId + " 执行完成：" + result.summary())
            return Result.success(result)
        } catch (t: Throwable) {
            loader?.failure?.let { return Result.failure(it) }
            val root = rootCause(t)
            if (root is JsPluginError) return Result.failure(root)
            return Result.failure(JsPluginError(STAGE_EXEC, "未预期失败：" + root.message, root))
        } finally {
            Context.exit()
        }
    }

    /**
     * 把 Rhino 包在异常外面的壳剥掉（WrappedException / JavaScriptException / cause 链），
     * 不剥回来调用方就只看到一句 "Wrapped ..."。
     */
    private fun rootCause(t: Throwable): Throwable {
        var cur: Throwable = t
        var guard = 0
        while (guard++ < 8 && cur !is JsPluginError) {
            val next = when {
                cur is WrappedException -> cur.wrappedException
                cur is JavaScriptException -> cur.value as? Throwable
                cur.cause != null && cur.cause !== cur -> cur.cause
                else -> null
            } ?: break
            cur = next
        }
        return cur
    }

    // ------------------------------------------------------------- 入口解析

    /** 入口文件在包内的相对路径 + 命中的规则名，两条都进日志。 */
    data class JsEntry(val path: String, val source: String)

    companion object {

        const val STAGE_ENTRY = "解析入口"
        const val STAGE_COMPILE = "编译"
        const val STAGE_EXEC = "执行"
        const val STAGE_HOST_API = "host api"

        /** 模块解析/加载阶段：裸模块、找不到文件、路径越界都报这个阶段。 */
        const val STAGE_LOAD = "模块加载"

        /**
         * 解析顺序（命中且文件真实存在即止）：
         *  1. package.json 的 dsh.main
         *  2. package.json 的 dsh.entry
         *     （dsh.bundle 是给别的端的打包产物，故意忽略）
         *  3. package.json 的 main
         *  4. index.js
         *  5. lib/index.js
         *
         * 入口只负责"从哪儿开始"；包内相对 import 由 [ModuleLoader] 递归解析。
         */
        fun resolveEntry(dir: File): JsEntry? {
            val pkg = runCatching { JSONObject(File(dir, "package.json").readText()) }.getOrNull()
            val dsh = pkg?.optJSONObject("dsh")
            val candidates = listOf(
                "dsh.main" to (dsh?.optString("main") ?: ""),
                "dsh.entry" to (dsh?.optString("entry") ?: ""),
                "main" to (pkg?.optString("main") ?: ""),
                "index.js" to "index.js",
                "lib/index.js" to "lib/index.js",
            )
            return candidates.firstOrNull { (_, rel) -> rel.isNotBlank() && File(dir, rel).isFile }
                ?.let { (source, rel) -> JsEntry(rel, source) }
        }

        /** 常量：死循环阈值（指令数）与单次执行总步数上限。 */
        const val OBSERVER_THRESHOLD = 100_000
        const val MAX_STEPS = 5_000_000L
        const val HTTP_TIMEOUT_MS = 10_000
    }

    // -------------------------------------------------------------- 沙箱

    /**
     * 解释模式 + 指令计数：Rhino 的 instruction observer 只在 optimizationLevel = -1
     * 时触发，所以强制解释执行 —— 慢一点，但死循环能被掐断。
     */
    private class BudgetFactory : ContextFactory() {

        override fun makeContext(): Context = super.makeContext().also {
            it.optimizationLevel = -1
            it.instructionObserverThreshold = OBSERVER_THRESHOLD
            it.languageVersion = Context.VERSION_ES6
            // 反射逃逸靠 initSafeStandardObjects()：它不挂 Packages / java / getClass，
            // 所以脚本里 typeof java === "undefined"，拿不到任何宿主类。
        }

        private var executed = 0L

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            executed += instructionCount
            if (executed > MAX_STEPS) {
                throw EvaluatorException("JS 执行步数超过上限 " + MAX_STEPS + "，疑似死循环")
            }
        }
    }

    // ---------------------------------------------------------- host api

    /** 收集脚本通过 dsh.ctx 注册的一切。脚本失败时结果一律作废。 */
    private class Collector(
        private val pluginId: String,
        private val entryPath: String,
        private val config: Map<String, String>,
        private val onSetConfig: (String, String) -> Unit,
    ) {

        private val contributions = mutableListOf<ContributionSpec>()
        private val commands = mutableListOf<JsCommand>()
        private val composerButtons = mutableListOf<JsComposerButton>()
        private val renderers = mutableListOf<JsRenderer>()
        private val prompts = mutableListOf<String>()
        private val logs = mutableListOf<String>()
        private var failure: JsPluginError? = null

        fun result(): JsRunResult = JsRunResult(
            pluginId = pluginId,
            entryPath = entryPath,
            contributions = contributions.toList(),
            commands = commands.toList(),
            composerButtons = composerButtons.toList(),
            renderers = renderers.toList(),
            prompts = prompts.toList(),
            logs = logs.toList(),
        )

        fun resultOrFailure(): Result<JsRunResult> =
            failure?.let { Result.failure(it) } ?: Result.success(result())

        /** 建 dsh / ctx 两个对象，整个脚本能看见的宿主面就这么多。 */
        fun install(scope: Scriptable, cx: Context) {
            val ctx = cx.newObject(scope)

            fun put(name: String, value: Any?) = ScriptableObject.putProperty(ctx, name, value)
            fun fn(name: String, body: (Array<Any?>) -> Any?) = put(name, JsFn(name, body))

            put("pluginId", pluginId)
            fn("log") { args ->
                logs += args.firstOrNull()?.toString().orEmpty()
                undefined()
            }
            fn("config") { args ->
                val key = args.getOrNull(0)?.toString().orEmpty()
                val fallback = args.getOrNull(1)?.toString().orEmpty()
                config[key] ?: fallback
            }
            fn("setConfig") { args ->
                val key = args.getOrNull(0)?.toString().orEmpty()
                val value = args.getOrNull(1)?.toString().orEmpty()
                if (key.isBlank()) fail(STAGE_HOST_API, "setConfig 缺少 key") else onSetConfig(key, value)
                undefined()
            }
            fn("registerContribution") { args ->
                val o = args.firstOrNull() as? Scriptable
                if (o == null) {
                    fail(STAGE_HOST_API, "registerContribution 需要对象参数")
                } else {
                    val slot = o.str("slot")
                    if (slot.isBlank()) {
                        fail(STAGE_HOST_API, "registerContribution 缺少 slot")
                    } else {
                        contributions += ContributionSpec(
                            slot = slot,
                            id = o.str("id").ifBlank { pluginId + ".c" + (contributions.size + 1) },
                            label = o.str("label").ifBlank { o.str("id") },
                            subtitle = o.str("subtitle"),
                            icon = o.str("icon").ifBlank { null },
                            action = o.obj("action")?.toActionSpec(),
                            ui = o.obj("ui")?.toJsonObject(),
                        )
                    }
                }
                undefined()
            }
            fn("command") { args ->
                val o = args.firstOrNull() as? Scriptable
                if (o == null || o.str("id").isBlank()) {
                    fail(STAGE_HOST_API, "command 需要 id")
                } else {
                    commands += JsCommand(o.str("id"), o.str("label").ifBlank { o.str("id") }, o.str("payload"))
                }
                undefined()
            }
            fn("composerButton") { args ->
                val o = args.firstOrNull() as? Scriptable
                if (o == null || o.str("id").isBlank()) {
                    fail(STAGE_HOST_API, "composerButton 需要 id")
                } else {
                    composerButtons += JsComposerButton(
                        o.str("id"),
                        o.str("label").ifBlank { o.str("id") },
                        o.str("text"),
                    )
                }
                undefined()
            }
            fn("renderer") { args ->
                val o = args.firstOrNull() as? Scriptable
                if (o == null || o.str("id").isBlank() || o.str("template").isBlank()) {
                    fail(STAGE_HOST_API, "renderer 需要 id 和 template")
                } else {
                    val r = JsRenderer(o.str("id"), o.str("matcher"), o.str("template"))
                    renderers += r
                }
                undefined()
            }
            fn("http") { args ->
                val o = args.firstOrNull() as? Scriptable
                if (o == null || o.str("url").isBlank()) {
                    fail(STAGE_HOST_API, "http 需要 url")
                    undefined()
                } else {
                    http(o.str("method").ifBlank { "GET" }, o.str("url"), o.str("body").ifBlank { null })
                }
            }
            fn("prompt") { args ->
                val text = args.firstOrNull()?.toString().orEmpty()
                if (text.isNotBlank()) prompts += text
                undefined()
            }

            val dsh = cx.newObject(scope)
            ScriptableObject.putProperty(dsh, "ctx", ctx)
            ScriptableObject.putProperty(scope, "dsh", dsh)
            // 也挂到全局：两种写法（dsh.ctx.x / ctx.x）指向同一个对象。
            ScriptableObject.putProperty(scope, "ctx", ctx)
        }

        private fun undefined(): Any = Context.getUndefinedValue()

        private fun fail(stage: String, message: String) {
            if (failure == null) failure = JsPluginError(stage, message)
        }

        /**
         * 同步 HTTP：2xx 回正文，其余抛错。10s 超时，失败只影响当前插件。
         *
         * ponytail: 直接跑在调用线程上（插件装配在 IO 线程），没有取消、没有并发上限。
         * 上限就是主线程调用方会被一次慢请求拖住 —— 升级路径是改成 coroutine + 超时封装。
         */
        private fun http(method: String, url: String, body: String?): Any {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.requestMethod = method.uppercase()
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = HTTP_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("HTTP " + code + " · " + url)
                text
            } finally {
                conn.disconnect()
            }
        }
    }
}

// ----------------------------------------------------------------- 结果模型

/** 一次 JS 执行收集到的全部注册物。宿主侧由 [JsBackedPlugin] 消费。 */
data class JsRunResult(
    val pluginId: String,
    val entryPath: String = "",
    val contributions: List<ContributionSpec> = emptyList(),
    val commands: List<JsCommand> = emptyList(),
    val composerButtons: List<JsComposerButton> = emptyList(),
    val renderers: List<JsRenderer> = emptyList(),
    val prompts: List<String> = emptyList(),
    val logs: List<String> = emptyList(),
) {
    fun summary(): String =
        "贡献 " + contributions.size + " / 命令 " + commands.size +
            " / 输入框按钮 " + composerButtons.size + " / 渲染器 " + renderers.size +
            " / 待发提示词 " + prompts.size
}

data class JsCommand(val id: String, val label: String, val payload: String)

data class JsComposerButton(val id: String, val label: String, val text: String)

/**
 * 消息正文模板渲染器。matcher 目前只做原文保存 —— 宿主还没有「按 matcher 自动
 * 选中渲染器」的通道，消息的 rendererId 由宿主写入，这里不抢这个决定权。
 */
data class JsRenderer(val id: String, val matcher: String, val template: String) {
    /** 唯一真正执行的渲染动作：{{text}} 占位换成消息正文。 */
    fun render(text: String): String = template.replace("{{text}}", text)
}

/** 插件失败时携带阶段，调用方据此知道卡在解析、编译、执行还是宿主 API。 */
class JsPluginError(
    val stage: String,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception("[" + stage + "] " + message, cause)

// ------------------------------------------------------------ 模块系统

/**
 * 插件包内的最小 ESM 模块系统。
 *
 * 只解析包内相对路径（./x、../x，缺后缀补 .js，目录补 /index.js）；裸模块
 * （react、@scope/xxx、node:fs）不解析 —— 本端不装 npm 依赖，直接报 STAGE_LOAD。
 * 同一个文件只执行一次，返回值取自 module.exports。
 *
 * 每个模块有自己的子 scope（parentScope = 全局），所以顶层 var/function 落在模块
 * 自己的作用域里，不会串到别的插件；dsh / ctx 通过原型链照样看得见。
 *
 * ponytail: 没有 package.json 的 "exports" 条件导出、没有 node_modules 解析、
 * 没有 .json 模块、没有严格模式。要这些就换成真正的 CommonJS/ESM 实现。
 */
private class ModuleLoader(
    private val cx: Context,
    private val root: Scriptable,
    rootDir: File,
    private val log: (String) -> Unit,
) {

    private val rootPath: String = rootDir.canonicalFile.path

    /** 规范路径 -> module.exports。先入缓存再执行，循环依赖拿到同一份导出对象。 */
    private val cache = HashMap<String, Any?>()

    /**
     * __require 里真正想抛的失败。
     *
     * Rhino 的宿主函数出口只认 RuntimeException / Error，直接抛 checked 的
     * [JsPluginError] 会在解释器里炸成 ClassCastException（cannot be cast to
     * java.lang.Error）。所以先把它存在这儿，再往 JS 侧抛一个普通 RuntimeException，
     * 由 [JsPluginRuntime.execute] 取回这份带阶段的原始错误。
     */
    var failure: JsPluginError? = null
        private set

    fun load(file: File): Any? {
        val canonical = file.canonicalFile
        if (!inside(canonical)) {
            throw JsPluginError(JsPluginRuntime.STAGE_LOAD, "模块路径越界，拒绝加载插件目录之外的文件：" + canonical.path)
        }
        if (cache.containsKey(canonical.path)) return cache[canonical.path]

        val source = try {
            canonical.readText()
        } catch (t: Throwable) {
            throw JsPluginError(JsPluginRuntime.STAGE_LOAD, "模块读取失败：" + rel(canonical), t)
        }
        val transformed = try {
            EsmTransform.transform(source, rel(canonical))
        } catch (e: EsmTransform.UnsupportedException) {
            throw JsPluginError(JsPluginRuntime.STAGE_COMPILE, e.message ?: "ESM 变换失败", e)
        }

        val exports = cx.newObject(root)
        val module = cx.newObject(root)
        ScriptableObject.putProperty(module, "exports", exports)
        cache[canonical.path] = exports

        val moduleScope = cx.newObject(root)
        ScriptableObject.putProperty(moduleScope, "__exports", exports)
        ScriptableObject.putProperty(moduleScope, "module", module)
        ScriptableObject.putProperty(
            moduleScope,
            "__require",
            JsFn("__require") { args ->
                val spec = args.firstOrNull()?.toString().orEmpty()
                try {
                    if (spec.isBlank()) {
                        throw JsPluginError(JsPluginRuntime.STAGE_LOAD, "__require 缺少模块名")
                    }
                    load(resolve(spec, canonical.parentFile ?: File(rootPath)))
                } catch (e: JsPluginError) {
                    if (failure == null) failure = e
                    throw RuntimeException(e.message)
                }
            },
        )
        ScriptableObject.putProperty(moduleScope, "__interop", JsFn("__interop") { args -> interop(args.firstOrNull()) })
        // import.meta.url：给模块自己真实的 file:// 地址，插件靠它 dirname 出包目录。
        ScriptableObject.putProperty(moduleScope, "__metaUrl", "file://" + canonical.absolutePath)
        // import.meta.resolve：本端没有模块解析图，抛错让插件自带的 catch 回落。
        ScriptableObject.putProperty(
            moduleScope,
            "__metaResolve",
            JsFn("__metaResolve") { args ->
                throw RuntimeException("import.meta.resolve 本端不支持：" + args.firstOrNull()?.toString().orEmpty())
            },
        )

        val compiled = try {
            cx.compileString(transformed, rel(canonical), 1, null)
        } catch (t: Throwable) {
            cache.remove(canonical.path)
            throw JsPluginError(JsPluginRuntime.STAGE_COMPILE, rel(canonical) + " 编译失败：" + t.message, t)
        }
        try {
            compiled.exec(cx, moduleScope)
        } catch (t: Throwable) {
            cache.remove(canonical.path)
            throw t
        }

        val raw = ScriptableObject.getProperty(module, "exports")
        val value: Any? = if (raw == Scriptable.NOT_FOUND) exports else raw
        cache[canonical.path] = value
        log("模块执行：" + rel(canonical))
        return value
    }

    /** spec -> 磁盘文件。裸模块直接拒绝；路径逃逸在找文件之前就拒。 */
    private fun resolve(spec: String, fromDir: File): File {
        if (!spec.startsWith(".") && !spec.startsWith("/")) {
            throw JsPluginError(
                JsPluginRuntime.STAGE_LOAD,
                "该插件依赖外部模块 \"" + spec + "\"，本端不安装 npm 依赖",
            )
        }
        val base = File(fromDir, spec)
        if (!inside(base.canonicalFile)) {
            throw JsPluginError(
                JsPluginRuntime.STAGE_LOAD,
                "模块路径越界，拒绝加载插件目录之外的文件：\"" + spec + "\"",
            )
        }
        val candidates = listOf(
            base,
            File(base.path + ".js"),
            File(base.path + ".mjs"),
            File(base.path + ".cjs"),
            File(base, "index.js"),
        )
        val hit = candidates.firstOrNull { it.isFile }
            ?: throw JsPluginError(
                JsPluginRuntime.STAGE_LOAD,
                "包内找不到模块 \"" + spec + "\"（基准 " + rel(fromDir) + "）",
            )
        val canonical = hit.canonicalFile
        if (!inside(canonical)) {
            throw JsPluginError(
                JsPluginRuntime.STAGE_LOAD,
                "模块路径越界，拒绝加载插件目录之外的文件：\"" + spec + "\"",
            )
        }
        return canonical
    }

    /** 导出对象上有 default 就取 default（对应 import x from），否则整份导出（CJS 互操作）。 */
    private fun interop(value: Any?): Any? {
        if (value is Scriptable) {
            val d = ScriptableObject.getProperty(value, "default")
            if (d != Scriptable.NOT_FOUND) return d
        }
        return value
    }

    private fun inside(file: File): Boolean {
        val path = file.canonicalFile.path
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    private fun rel(file: File): String {
        val path = file.canonicalFile.path
        val rel = path.removePrefix(rootPath).removePrefix(File.separator)
        return if (rel.isEmpty()) file.name else rel
    }
}

// ------------------------------------------------------------ Rhino 胶水

/** 把 Kotlin lambda 包成 JS 可调用函数：脚本看到的 host API 全是它。 */
private class JsFn(
    private val name: String,
    private val body: (Array<Any?>) -> Any?,
) : BaseFunction() {

    override fun getFunctionName(): String = name

    override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? =
        body(args)
}

private fun Scriptable.str(key: String): String {
    val v = ScriptableObject.getProperty(this, key)
    return if (v == Scriptable.NOT_FOUND || v == null) "" else v.toString()
}

private fun Scriptable.obj(key: String): Scriptable? =
    ScriptableObject.getProperty(this, key) as? Scriptable

/** JS 对象 → JSONObject；只服务 ui / action 两个结构化字段。 */
private fun Scriptable.toJsonObject(): JSONObject {
    val out = JSONObject()
    ids.forEach { key ->
        val name = key.toString()
        val v = when (key) {
            is Int -> ScriptableObject.getProperty(this, key)
            else -> ScriptableObject.getProperty(this, name)
        }
        out.put(name, jsToJson(v))
    }
    return out
}

private fun jsToJson(value: Any?): Any? = when {
    value == null || value == Scriptable.NOT_FOUND -> JSONObject.NULL
    value is Scriptable && value.className == "Array" -> {
        val arr = JSONArray()
        val len = (ScriptableObject.getProperty(value, "length") as? Number)?.toInt() ?: 0
        for (i in 0 until len) arr.put(jsToJson(ScriptableObject.getProperty(value, i)))
        arr
    }
    value is Scriptable -> value.toJsonObject()
    value is Double -> if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong() else value
    else -> value
}

/** action 对象 → ActionSpec，语义与 plugin.json 的 action 字段一致。 */
private fun Scriptable.toActionSpec(): ActionSpec = ActionSpec(
    kind = str("kind").ifBlank { "prompt" },
    text = str("text"),
    method = str("method").ifBlank { "GET" },
    url = str("url"),
    body = str("body"),
    uri = str("uri"),
    pageId = str("pageId"),
    custom = str("custom"),
)