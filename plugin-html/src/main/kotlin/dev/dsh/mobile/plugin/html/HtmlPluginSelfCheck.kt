package dev.dsh.mobile.plugin.html

import dev.dsh.mobile.pluginapi.PluginCapability

/**
 * 纯 Kotlin 自检（不碰 Android、不跑 JS）：把「现场排错最想确认的几件事」
 * 变成一串可日志的断言。register() 里跑一次，AppLog 里能看到全部结论。
 *
 * 断言对象是打包进插件的 render.js 原文，所以能立刻发现「资源没打进去 / 版本对不上」。
 */
object HtmlPluginSelfCheck {

    fun run(): List<String> {
        val out = ArrayList<String>(32)

        fun check(name: String, ok: Boolean) {
            out += (if (ok) "OK   " else "FAIL ") + name
        }

        val js = RenderScript.JS
        val v = RenderScript.VERSION
        check("render.js 来自 classpath 资源 (" + js.length + " chars)", js.length > 12000)
        check("render.js 版本 = $v", js.contains("var VERSION = '$v'") && js.contains("@version $v"))
        check("导出 DSH_RENDER", js.contains("global.DSH_RENDER"))
        check("导出 render/body/css/tex/sanitize",
            js.contains("render: render") && js.contains("body: body") &&
                js.contains("tex: texHtml") && js.contains("sanitize: sanitize"))
        check("跟随 prefers-color-scheme", js.contains("prefers-color-scheme"))
        check("LaTeX 分式/根号/矩阵",
            js.contains(".tex-frac") && js.contains(".tex-sqrt") && js.contains(".tex-matrix"))
        check("LaTeX cases/aligned 环境", js.contains("cases") && js.contains("aligned"))
        check("LaTeX 定界符 \\left/\\right", js.contains("tex-delim"))
        check("块级公式 math-block", js.contains("math-block"))
        check("Markdown 表格 + 对齐", js.contains("md-table") && js.contains("ta-c"))
        check("Markdown 围栏代码", js.contains("md-code"))
        check("代码高亮词表", js.contains("hl-kw") && js.contains("hl-str") && js.contains("hl-com"))
        check("任务列表 md-task", js.contains("md-task") && js.contains("md-check"))
        check("嵌套列表递归", js.contains("function listHtml"))
        check("白名单清洗 sanitize", js.contains("function sanitize"))
        check("清洗事件属性", js.contains("indexOf('on') === 0"))
        check("清洗 javascript: 协议", js.contains("SAFE_URL"))
        check("清洗 srcdoc/@import", js.contains("srcdoc") && js.contains("expression"))

        check("HtmlDocument 带测高桥", HtmlDocumentBridgePresent)

        val manifest = HtmlPlugin().manifest
        check("manifest.id = " + HtmlPlugin.ID, manifest.id == HtmlPlugin.ID)
        check("manifest.version 与 render.js 同源", manifest.version == v)
        check("manifest 声明 MessageRenderer", PluginCapability.MessageRenderer in manifest.capabilities)
        check("renderer id = html.web", HtmlPlugin.RENDERER_ID == "html.web")
        check("入口类可反射无参构造",
            runCatching { HtmlPlugin::class.java.getDeclaredConstructor().newInstance() }.isSuccess)
        return out
    }

    /** 桥脚本常量在 HtmlDocument 里；这里只能间接确认它没被删。 */
    private val HtmlDocumentBridgePresent: Boolean =
        runCatching {
            HtmlDocument::class.java.getDeclaredField("BRIDGE")
        }.isSuccess
}