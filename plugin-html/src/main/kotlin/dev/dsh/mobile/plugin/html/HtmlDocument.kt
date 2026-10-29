package dev.dsh.mobile.plugin.html

import android.content.Context
import org.json.JSONObject

/**
 * 拼出交给 WebView 的整页 HTML。
 *
 * 消息正文不进 DOM，而是先 JSON 转义成一条 JS 字符串常量，再由 render.js 解析成 HTML ——
 * 正文里的尖括号和 & 不会破坏文档结构，也不需要 addJavascriptInterface（没有任何 JS→Java 的桥）。
 *
 * 正文里的 `<style>` / `<script>` 先抽出来再单独注入：render.js 的 sanitize 会整段删掉它们，
 * 而模型的 CSS 动画（@keyframes）、SVG 动画和脚本都在里面 —— 直接删就是「动态页面渲染成静态图」。
 * 安全性不变：WebView 已 blockNetworkLoads、无 JS 桥、allowFileAccess=false。
 *
 * 2.0 起额外注入一段 [BRIDGE_ID] 桥脚本：把根节点高度 / 图片加载 / 字体就绪 / 脚本执行完的时间点
 * 回报给宿主，宿主据此决定还要测几次高度 —— 不再盲等固定 400ms。
 */
internal object HtmlDocument {

    private val FENCE = Regex("(?is)^\\s*```+[ \\t]*([a-zA-Z0-9_+-]*)[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n?\\s*```+\\s*$")

    /**
     * 模型经常把整份 HTML 包在 ```html 围栏里交回来。围栏留着的话 render.js 会当代码块转义显示，
     * 用户看到的是源码而不是页面。这里只在「整段正文就是一个围栏块」时剥壳；正文里夹着
     * 围栏的情况仍交给 markdown 正常处理。
     */
    private fun unwrapFence(text: String): String {
        val m = FENCE.find(text) ?: return text
        val lang = m.groupValues[1].lowercase()
        val inner = m.groupValues[2]
        if (lang.isNotEmpty() && lang != "html" && lang != "htm") return text
        return inner
    }

    private val STYLE_BLOCK = Regex("(?is)<style\\b[^>]*>(.*?)</style\\s*>")
    private val SCRIPT_BLOCK = Regex("(?is)<script\\b[^>]*>(.*?)</script\\s*>")
    private val SHELL_TAG = Regex("(?is)<!doctype[^>]*>|</?(html|head|body)\\b[^>]*>")

    /** 整份文档 vs 一段 markdown：只有文档才跳过 markdown 解析。 */
    private val DOC_MARK = Regex("(?is)<!doctype\\s+html|<html\\b|<body\\b")

    private class Parts(val body: String, val styles: String, val scripts: String, val document: Boolean)

    private fun split(text: String): Parts {
        val styles = STYLE_BLOCK.findAll(text).joinToString("\n") { it.groupValues[1] }
        val scripts = SCRIPT_BLOCK.findAll(text).joinToString("\n;\n") { it.groupValues[1] }
        var body = text
        if (styles.isNotEmpty()) body = body.replace(STYLE_BLOCK, "")
        if (scripts.isNotEmpty()) body = body.replace(SCRIPT_BLOCK, "")
        body = body.replace(SHELL_TAG, "")
        return Parts(body, styles, scripts, DOC_MARK.containsMatchIn(text))
    }

    /** JSON 转义成 JS 字符串字面量；`</` 折开，避免提前闭合外层 script 标签。 */
    private fun jsString(value: String): String =
        JSONObject.quote(value).replace("</", "<\\/")

    fun build(context: Context, text: String): String {
        // T4：超大正文也整份交给 render.js，不再按长度截断成纯文本页（否则「展开全文」拿不到末行）。
        // ponytail: 上限只剩 WebView 自己；真要限制，按节点数/耗时降级，别按字符数砍内容。
        val unwrapped = unwrapFence(text.trim())
        val parts = split(unwrapped)
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\">")
            append("<style id=\"dsh-css\"></style>")
            append("<style id=\"dsh-user-css\"></style>")
            append("<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden;}</style>")
            append("</head><body><div id=\"dsh-root\"></div>")
            append("<script>").append(RenderAssets.js(context)).append("</script>")
            append("<script>var DSH_DOC = ").append(if (parts.document) "true" else "false").append(";</script>")
            append("<script>var DSH_SRC = ").append(jsString(parts.body)).append(";</script>")
            append("<script>var DSH_CSS = ").append(jsString(parts.styles)).append(";</script>")
            append("<script>var DSH_JS = ").append(jsString(parts.scripts)).append(";</script>")
            append("<script>").append(BRIDGE).append("</script>")
            append("<script>(function(){")
            append("var r=window.DSH_RENDER;")
            append("if(!r){document.getElementById('dsh-root').textContent='render.js \u672a\u52a0\u8f7d';DSH.notify('error');return;}")
            append("try{document.getElementById('dsh-css').textContent=r.css();}catch(e){}")
            append("try{document.getElementById('dsh-user-css').textContent=DSH_CSS;}catch(e){}")
            append("var MODE=r.doc&&DSH_DOC?'doc':'body';")
            append("try{document.getElementById('dsh-root').innerHTML=r[MODE](DSH_SRC);}catch(e){")
            append("document.getElementById('dsh-root').textContent=DSH_SRC;}")
            append("if(DSH_JS){var s=document.createElement('script');s.textContent=DSH_JS;document.body.appendChild(s);}")
            append("DSH.bind();DSH.notify('rendered');")
            append("})();</script>")
            append("</body></html>")
        }
    }

    /**
     * 桥：把「内容可能变高」的时刻回报给宿主（宿主侧对应 HtmlWebView 的 message 监听）。
     * 只做通知，不传数据 —— 高度仍由宿主用 evaluateJavascript 自己量，避免字符串注入面。
     */
    private val BRIDGE = """
        |(function(g){
        |  var sent = {};
        |  g.DSH = {
        |    version: '2.0.0',
        |    notify: function(why){
        |      if (sent[why]) return;
        |      sent[why] = 1;
        |      try { window.DSH_BRIDGE && window.DSH_BRIDGE.postMessage(why); } catch (e) {}
        |    },
        |    bind: function(){
        |      var imgs = document.images, n = imgs.length, done = 0;
        |      if (!n) return;
        |      for (var i = 0; i < n; i++) {
        |        if (imgs[i].complete) { done++; continue; }
        |        imgs[i].addEventListener('load', function(){ done++; if (done >= n) g.DSH.notify('images'); });
        |        imgs[i].addEventListener('error', function(){ done++; if (done >= n) g.DSH.notify('images'); });
        |      }
        |    }
        |  };
        |  if (document.fonts && document.fonts.ready) {
        |    document.fonts.ready.then(function(){ g.DSH.notify('fonts'); });
        |  }
        |  window.addEventListener('load', function(){ if (g.DSH) g.DSH.notify('load'); });
        |})(window);
    """.trimMargin()
}