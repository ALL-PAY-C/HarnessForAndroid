package dev.dsh.mobile.app.nav

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import dev.dsh.mobile.app.AppContainer
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.pluginhost.gatewayUiUrl

/**
 * 插件自带 WebUI 的全屏页（网关 http 地址 + 独立 WebView）。
 *
 * 为什么不复用 PluginUiNode.Html：那条路把页面塞进卡片里的 HtmlMessageView，那里
 * `blockNetworkLoads = true` 且 base 是 about:blank —— 页面既发不出 fetch，相对地址也
 * 解析不到 host，配置页提交不了。这里同源加载 `http://127.0.0.1:<port>/<bare>/ui`：
 * 请求、POST、localStorage 全按浏览器规则走，不需要 CORS。
 */
@Composable
internal fun PluginWebUiRoute(
    container: AppContainer,
    nav: NavHostController,
    entry: NavBackStackEntry,
) {
    val pluginId = entry.arguments?.getString("pluginId").orEmpty()
    // 地址在页面内从配置算：路由只带 id，端口回写改了配置下次进来就是新端口。
    val url = remember(pluginId) {
        gatewayUiUrl(container.installedPlugins.config(pluginId)["baseUrl"], pluginId)
    }
    val title = remember(pluginId) {
        container.installedPlugins.get(pluginId)?.name?.takeIf { it.isNotBlank() } ?: pluginId
    }
    PluginWebUiPage(pluginId = pluginId, url = url, title = title, onBack = { nav.popBackStack() })
}

@Composable
internal fun PluginWebUiPage(
    pluginId: String,
    url: String?,
    title: String,
    onBack: () -> Unit,
) {
    val p = LocalQq.current
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqTopBar(
                leading = { IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack) },
            ) {
                Text(title, color = p.t1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            if (url == null) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    Text("插件界面地址还没准备好：" + pluginId, color = p.t3, fontSize = 13.sp)
                }
            } else {
                WebUiBody(url)
            }
        }
    }
}

/** WebView 实例挂在普通字段里：它是平台对象，不该参与重组比较。 */
private class WebHolder {
    var view: WebView? = null
}

@Composable
private fun WebUiBody(url: String) {
    val p = LocalQq.current
    val holder = remember(url) { WebHolder() }
    var failed by remember(url) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // 插件页面自己用 fetch/XHR 打同源网关，脚本与 localStorage 必须开；
                    // file/content 一行都不给 —— 页面只许走 http(s)。
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            failed = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            // 子资源失败不该把整页判死：只认主框架。
                            if (request.isForMainFrame) failed = true
                        }
                    }
                    holder.view = this
                    loadUrl(url)
                }
            },
        )
        if (failed) {
            Column(Modifier.fillMaxSize().background(p.bg).padding(16.dp)) {
                Text("插件界面打不开：" + url, color = p.t3, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    failed = false
                    holder.view?.loadUrl(url)
                }) {
                    Text("重试")
                }
            }
        }
    }

    // 页面销毁必须自己收：WebView 是 Activity 级资源，漏掉就是整块内存跟着不释放。
    DisposableEffect(holder) {
        onDispose {
            holder.view?.apply {
                stopLoading()
                destroy()
            }
            holder.view = null
        }
    }
}
