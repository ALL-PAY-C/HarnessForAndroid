package dev.dsh.mobile.plugin.html.diagram

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.mobile.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图形卡片里的 WebView 宿主。
 *
 * 与 HtmlMessageView 同一套思路：页面不往 Java 传内容，只调 DSH_BRIDGE.postMessage(reason)
 * 喊一声「我要变高了」，宿主自己 evaluateJavascript 量高度。
 *
 * interactive=true 是给全屏用的：打开 WebView 原生双指缩放与滚动；卡片内 interactive=false，
 * 手势全交给 Compose，避免列表滚动被 WebView 抢走。
 */
@Composable
internal fun DiagramWeb(
    doc: String,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onHeight: (Float) -> Unit = {},
    onAspect: (Float) -> Unit = {},
    onCreated: (WebView?) -> Unit = {},
) {
    val context = LocalContext.current
    var holder by remember { mutableStateOf<WebView?>(null) }
    // 释放离线库要读 8MB、还可能扫已安装包，放在组合期会直接把主线程拖到 ANR（实测踩到过）。
    // 先给个空串表示「还没好」，等 IO 回来再建 WebView。
    var base by remember(context) { mutableStateOf("") }
    LaunchedEffect(context) {
        base = withContext(Dispatchers.IO) {
            DiagramAssets.dir(context)?.let { "file://" + it.absolutePath + "/" } ?: ""
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            holder?.let { view ->
                view.stopLoading()
                view.removeJavascriptInterface(BRIDGE_NAME)
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            }
            holder = null
            onCreated(null)
        }
    }

    val url = base
    if (url.isNotEmpty()) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                buildDiagramWebView(ctx, interactive, url, onHeight, onAspect).also {
                    holder = it
                    onCreated(it)
                }
            },
            update = { view ->
                // doc 变了才重载：Compose 每次重组都 load 一次会让手势与滚动状态一直被打断
                if (view.tag != doc) {
                    view.tag = doc
                    view.loadDataWithBaseURL(url, doc, "text/html", "utf-8", null)
                }
            },
        )
    }
}

private const val BRIDGE_NAME = "DSH_BRIDGE"

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
private fun buildDiagramWebView(
    context: Context,
    interactive: Boolean,
    base: String,
    onHeight: (Float) -> Unit,
    onAspect: (Float) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    // 离线库与页面都不联网；plantuml 的 SVG 是 Java 层下回来的，不进 WebView
    settings.blockNetworkLoads = true
    // allowFileAccess 是全局开关，真正把作用域限死在刚复制出来的目录的是 base URL：
    // 页面里只有相对路径 <script src="mermaid.min.js">，解析目标只能是 diagram-v1/
    settings.allowFileAccess = true
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = true
    settings.allowContentAccess = false
    settings.domStorageEnabled = false
    settings.databaseEnabled = false
    settings.cacheMode = WebSettings.LOAD_NO_CACHE
    settings.textZoom = 100
    settings.setSupportZoom(interactive)
    settings.builtInZoomControls = interactive
    settings.displayZoomControls = false
    setBackgroundColor(Color.TRANSPARENT)
    isVerticalScrollBarEnabled = interactive
    isHorizontalScrollBarEnabled = false
    overScrollMode = WebView.OVER_SCROLL_NEVER
    isLongClickable = false
    addJavascriptInterface(
        HeightBridge(onPing = { measure(this, onHeight) }, onAspect = onAspect),
        BRIDGE_NAME,
    )
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            AppLog.w("dsh.diagram console " + msg.message() + " @" + msg.lineNumber())
            return true
        }
    }
    webViewClient = object : WebViewClient() {
        // 一律拦掉跳转：图形里不该出现可点的外链
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

        override fun onPageFinished(view: WebView, url: String?) {
            measure(view, onHeight)
            // 库是异步的（mermaid.run / Viz.instance / echarts 首次布局），按几个时间点补测
            listOf(150L, 400L, 900L, 2000L).forEach { delay ->
                view.postDelayed({ measure(view, onHeight) }, delay)
            }
        }
    }
}

/**
 * JS 只调 postMessage(为什么)，Java 侧不回收任何页面内容：
 * - 'rendered'（默认）当作「再量一次」的信号；
 * - 'fit:<高宽比>' 是 SVG 页算出的 viewBox 高/宽（纯数字），宿主据此按容器宽度定高。
 */
private class HeightBridge(
    private val onPing: () -> Unit,
    private val onAspect: (Float) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(reason: String?) {
        val ratio = reason?.takeIf { it.startsWith(ASPECT_PREFIX) }
            ?.substring(ASPECT_PREFIX.length)
            ?.toFloatOrNull()
        if (ratio != null && ratio > 0f) {
            post { onAspect(ratio) }
        } else {
            post(onPing)
        }
    }
}

private const val ASPECT_PREFIX = "fit:"

private fun post(block: () -> Unit) {
    android.os.Handler(android.os.Looper.getMainLooper()).post(block)
}

private fun measure(view: WebView, onHeight: (Float) -> Unit) {
    view.evaluateJavascript(MEASURE_JS) { raw ->
        val px = raw?.trim()?.removeSurrounding("\"")?.toFloatOrNull()
        if (px != null && px > 0f) onHeight(px)
    }
}

/**
 * 一棵树里量最大高度：#wrap（图形页）/ #dsh-root（html 类，复用 HtmlDocument）。
 * 交互式全屏不关心高度，但 measure 仍然安全（页面被撑满时高度就是视口高度）。
 */
private const val MEASURE_JS =
    "(function(){var ids=['wrap','out','dsh-root'];var h=0;" +
        "for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(!e)continue;" +
        "var r=e.getBoundingClientRect();if(r.height>h)h=r.height;if(e.scrollHeight>h)h=e.scrollHeight;}" +
        "var b=document.body,d=document.documentElement;" +
        "if(b&&b.scrollHeight>h)h=b.scrollHeight;if(d&&d.scrollHeight>h)h=d.scrollHeight;" +
        "return Math.ceil(h);})()"
