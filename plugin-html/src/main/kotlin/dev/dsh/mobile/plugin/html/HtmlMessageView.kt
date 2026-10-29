package dev.dsh.mobile.plugin.html

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.ChatMessage

/**
 * MessageRenderer 实现：把 ChatMessage.text 交给离线 render.js，渲染成网页视图。
 *
 * 安全：blockNetworkLoads 关网、allowFileAccess 关文件、正文里的 script/iframe/on* 由
 * render.js 白名单清洗掉。唯一的 JS→Java 通道是 [HeightBridge]，只触发一次重新测高，
 * 不传任何数据进来。
 */
@Composable
fun HtmlMessageView(message: ChatMessage, modifier: Modifier = Modifier) {
    HtmlWebView(html = message.text, modifier = modifier, key = message.id)
}

/**
 * 直接渲染一段 HTML（宿主 ```html 围栏走这里）。
 *
 * 高度自适应：WebView 不能自己报高度，所以三段兜底 ——
 * ① onPageFinished 立即量一次；② [HeightBridge] 在图片/字体/load 事件后回调再量；
 * ③ 200/600ms 各补一次，覆盖字体回流这类晚到变化。高度只增不减，取最大值。
 */
@Composable
fun HtmlWebView(html: String, modifier: Modifier = Modifier, key: String = html.take(64)) {
    val context = LocalContext.current
    val doc = remember(key, html) { HtmlDocument.build(context, html) }
    var boxHeight by remember(key) { mutableStateOf(MIN_HEIGHT) }
    var holder by remember(key) { mutableStateOf<WebView?>(null) }

    DisposableEffect(key) {
        onDispose {
            holder?.let { view ->
                view.stopLoading()
                view.removeJavascriptInterface(HeightBridge.NAME)
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            }
            holder = null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(boxHeight),
        factory = { ctx ->
            buildWebView(ctx) { px ->
                // evaluateJavascript 返回 CSS px；WebView 用 width=device-width,initial-scale=1，
                // 1 CSS px == 1dp，不能再除 density（除一次高度缩 2.6 倍，底部整块被裁）。
                val dp = px.dp.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
                if (dp > boxHeight + 0.5.dp) boxHeight = dp
            }.also { holder = it }
        },
        update = { view ->
            if (view.tag != doc) {
                view.tag = doc
                view.loadDataWithBaseURL("about:blank", doc, "text/html", "utf-8", null)
            }
        },
    )
}

/** JS 侧只调 `DSH_BRIDGE.postMessage(为什么)`；Java 侧只做「再量一次」，不收任何入参内容。 */
private class HeightBridge(private val onPing: () -> Unit) {
    @JavascriptInterface
    fun postMessage(reason: String?) {
        // reason 只用来触发重测，不记录、不转发，避免把页面内容带进 Java 层。
        post(onPing)
    }

    companion object {
        const val NAME = "DSH_BRIDGE"
    }
}

private fun post(block: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post { block() }
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
private fun buildWebView(context: Context, onHeight: (Float) -> Unit): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.textZoom = 100
        settings.setSupportZoom(false)
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = WebView.OVER_SCROLL_NEVER
        isLongClickable = false
        addJavascriptInterface(HeightBridge { measure(this, onHeight) }, HeightBridge.NAME)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                AppLog.w("dsh.html console " + msg.message() + " @" + msg.lineNumber())
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(DIAG_JS) { raw -> AppLog.i("dsh.html diag " + raw) }
                measure(view, onHeight)
                view.postDelayed({ measure(view, onHeight) }, 200L)
                view.postDelayed({ measure(view, onHeight) }, 600L)
            }
        }
    }

private fun measure(view: WebView, onHeight: (Float) -> Unit) {
    view.evaluateJavascript(MEASURE_JS) { raw ->
        val px = raw?.trim()?.removeSurrounding(QUOTE)?.toFloatOrNull()
        if (px != null && px > 0f) onHeight(px)
    }
}

/** 量内容高度，让 WebView 跟着内容长高（外面由 Compose 列表滚动）。 */
private const val MEASURE_JS =
    "(function(){var r=document.getElementById('dsh-root');" +
        "var a=r?r.getBoundingClientRect().height:0;" +
        "var b=document.body?document.body.scrollHeight:0;" +
        "var c=document.documentElement?document.documentElement.scrollHeight:0;" +
        "return Math.ceil(Math.max(a,Math.max(b,c)));})()"

private const val DIAG_JS =
    "(function(){var r=document.getElementById('dsh-root');" +
    "return 'render='+(!!window.DSH_RENDER)+' root='+(r?r.childElementCount:-1)+" +
    "' len='+(r?r.innerHTML.length:-1)+' h='+(r?Math.ceil(r.getBoundingClientRect().height):-1)+" +
    "' first='+(r&&r.firstChild?String(r.firstChild.nodeName):'none');})()"

private const val QUOTE = "\""
private val MIN_HEIGHT = 72.dp
private val MAX_HEIGHT = 2400.dp