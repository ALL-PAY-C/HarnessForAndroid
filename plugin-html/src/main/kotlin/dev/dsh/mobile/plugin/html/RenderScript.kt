package dev.dsh.mobile.plugin.html

import dev.dsh.mobile.core.common.AppLog

/**
 * render.js 的唯一真源是 `src/main/resources/render.js`（打进 APK 的 Java 资源，
 * 与 Kotlin 类同一个 ClassLoader 可见）。
 *
 * 老版本靠两份文本（assets/render.js + 这里的字符串常量）保持一致，改一处忘一处就悄悄失效；
 * 现在只有一份文件，[JS] 首次访问时读进内存，读不到才降级成极简渲染器 —— 渲染器永不缺席。
 */
object RenderScript {

    /** 与 render.js 头部 `@version` 同源；发版时一起改。 */
    const val VERSION: String = "2.0.0"

    private const val RESOURCE = "/render.js"

    @Volatile
    private var cached: String? = null

    val JS: String
        get() = cached ?: load().also { cached = it }

    private fun load(): String {
        val stream = RenderScript::class.java.getResourceAsStream(RESOURCE)
        if (stream == null) {
            AppLog.w("dsh.html: classpath 缺 $RESOURCE，使用降级渲染器")
            return FALLBACK_JS
        }
        val text = runCatching {
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.onFailure { AppLog.w("dsh.html: 读 $RESOURCE 失败，使用降级渲染器", it) }.getOrNull()
        if (text.isNullOrBlank()) return FALLBACK_JS
        if (!text.contains("DSH_RENDER")) {
            AppLog.w("dsh.html: $RESOURCE 内没有 DSH_RENDER 导出，使用降级渲染器")
            return FALLBACK_JS
        }
        return text
    }

    /** 只剩「把正文原样当预格式文本显示」的能力，用来保证消息不会整条消失。 */
    private val FALLBACK_JS: String = listOf(
        "(function(g){var e=function(s){return String(s==null?'':s).replace(/&/g,'&amp;')",
        ".replace(/</g,'&lt;').replace(/>/g,'&gt;');};",
        "g.DSH_RENDER={version:'0',render:function(t){return '<pre class=\\\"md-code\\\"><code>'+",
        "e(t)+'</code></pre>';},body:function(t){return this.render(t);},tex:function(t){return e(t);},",
        "sanitize:function(h){return h;},css:function(){return 'pre{white-space:pre-wrap;}';}};})(this);"
    ).joinToString("")
}