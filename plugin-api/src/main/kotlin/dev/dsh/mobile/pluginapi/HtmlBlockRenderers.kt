package dev.dsh.mobile.pluginapi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * ```html 围栏的渲染槽。
 *
 * 内置 Markdown 渲染器（:render）遇到 HTML 代码块时先看这里：
 * 装了 HTML 渲染插件（dsh.html）就用 WebView 真渲染，没装就退回源码视图。
 * 这样气泡、头像、思维链都留在宿主手里 —— 只有那一段 HTML 交给插件。
 *
 * 关键点：插件是在首帧之后才注册进来的（外部插件走 PathClassLoader 反射装载，
 * 冷启动时列表可能已经画过一屏）。所以这里除了槽位本身还带一个 [generation]，
 * 宿主在 Composable 里读它 → 插件注册那一下会触发重组，卡片从「源码」自动翻成「预览」，
 * 不必让用户退出重进。
 */
object HtmlBlockRenderers {

    /** 插件在 register() 里注册；宿主退出/卸载插件时置回 null。 */
    @Volatile
    var render: (@Composable (html: String) -> Unit)? = null
        private set

    /** 注册代数：读它就把当前 Composable 订阅到槽位变化上。 */
    var generation by mutableIntStateOf(0)
        private set

    /** 是否有插件接管了 HTML 围栏。 */
    val available: Boolean get() = render != null

    fun install(block: @Composable (html: String) -> Unit) {
        render = block
        generation++
    }

    /**
     * 旧名字，等价于 [install]。
     *
     * 已发布的 dsh-plugin-html-2.0.0.apk 是按这个签名编译的；删掉它插件侧会
     * NoSuchMethodError（"No virtual method setRender"）—— 插件是独立 APK，
     * 宿主的公开 API 一旦发布就不能只靠改名升级。保留这个别名，二进制兼容。
     */
    @Deprecated("改名 install()；这个别名只为已发布的插件 APK 保留", ReplaceWith("install(block)"))
    fun setRender(block: @Composable (html: String) -> Unit) = install(block)

    fun clear() {
        render = null
        generation++
    }
}
