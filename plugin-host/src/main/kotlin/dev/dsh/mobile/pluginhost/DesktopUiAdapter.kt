package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.pluginapi.PluginUiNode
import dev.dsh.mobile.pluginapi.PluginUiSpec
import dev.dsh.mobile.pluginapi.WidthPolicy
import java.io.File
import org.json.JSONObject

/**
 * 桌面插件 → 宿主声明式 UI 的适配器。
 *
 * 真实桌面 DSH 2.0.19-next 的插件是 npm 包，UI 本体是 React 代码 —— **React 组件树无法逐节点
 * 翻译**（它的 hooks、样式表、事件绑定在本端没有对应物）。所以这里只认三种「能落到宿主界面」
 * 的形态，按优先级：
 *
 * 1. `plugin.json` 的 `ui` / `panel` / `view` / `contribution.ui`；
 * 2. `package.json` 的 `dsh.ui` | `dsh.panel` | `dsh.view` | `dsh.contribution.ui`；
 * 3. 插件目录里的 HTML 面板（panel.html → ui.html → index.html，单文件上限 2MB），
 *    读出来交给 [dev.dsh.mobile.pluginapi.HtmlBlockRenderers] 通道（装了 dsh.html 就 WebView 真渲染）。
 *
 * 都没有就返回 null：调用方据此只显示信息卡，不伪造界面。解析失败只记日志 + 返回 null。
 */
object DesktopUiAdapter {

    /** HTML 面板与清单文件的大小上限；超了就当没有，避免把几百 MB 的 bundle 读进内存。 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** HTML 面板的查找顺序。 */
    private val PANEL_NAMES = listOf("panel.html", "ui.html", "index.html")


    /** 已安装插件目录 → spec。目录不存在 / 三种形态都没有 / 解析失败 都是 null。 */
    fun fromDirectory(dir: File?): PluginUiSpec? {
        if (dir == null || !dir.isDirectory) return null
        return runCatching {
            fromManifestFile(File(dir, "plugin.json"), dir)
                ?: fromManifestFile(File(dir, "package.json"), dir)
                ?: fromHtmlPanel(dir)
        }.getOrElse {
            warn("desktop ui read failed: " + dir.name, it)
            null
        }
    }

    /** 清单 JSON 文本（plugin.json 或 package.json 的内容）→ spec。 */
    fun fromJson(text: String?, baseDir: File? = null): PluginUiSpec? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(text)
            // 单凭文本判断：带 dsh 节点的按 npm 包解析，否则按 plugin.json 根部解析。
            specOf(root, root.optJSONObject("dsh") != null, baseDir)
        }.getOrElse {
            warn("desktop ui manifest unreadable", it)
            null
        }
    }

    /**
     * 已安装插件（清单 + 落盘目录）→ spec。
     *
     * 顺序：目录里的 plugin.json/package.json/HTML 面板 → 清单里每条 contribution 自带的 `ui`。
     * 后者是 [PluginManifestFile] 唯一留住的原始子树（根级 ui 只在文件里，见 fromDirectory）。
     */
    fun fromPlugin(manifest: PluginManifestFile?, dir: File? = null): PluginUiSpec? {
        fromDirectory(dir)?.let { return it }
        val declared = manifest?.contributions?.firstOrNull { it.ui != null }?.ui ?: return null
        return PluginUiJson.spec(declared)
    }

    // -------------------------------------------------------------- internals

    private fun specOf(root: JSONObject, isPackage: Boolean, baseDir: File?): PluginUiSpec? {
        val declared = declaredUi(root, isPackage) ?: return null
        return when (declared) {
            // 声明成子树：交给 PluginUiJson，未知节点自己降级。
            is JSONObject -> PluginUiJson.spec(declared)
            // 声明成文件名（dsh.panel = "panel.html"）：当 HTML 面板读。
            is String -> fromHtmlPanelFile(baseDir?.let { File(it, declared) } ?: File(declared))
            else -> null
        }
    }

    /**
     * 按优先级取出 `ui` 声明：plugin.json 看根部，package.json 看 `dsh` 里。
     * 命中空对象视同没写 —— 空子树画不出任何东西，等于没界面描述。
     */
    private fun declaredUi(root: JSONObject, isPackage: Boolean): Any? {
        val scope = if (isPackage) root.optJSONObject("dsh") ?: root else root
        val contribution = scope.optJSONObject("contribution")
        val candidates = listOf(
            scope.opt("ui"),
            scope.opt("panel"),
            scope.opt("view"),
            contribution?.opt("ui"),
        )
        for (candidate in candidates) {
            when (candidate) {
                is JSONObject -> if (candidate.length() > 0) return candidate
                is String -> if (candidate.isNotBlank()) return candidate
            }
        }
        return null
    }

    private fun fromManifestFile(file: File, dir: File): PluginUiSpec? {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val text = runCatching { file.readText() }.getOrNull()
        return fromJson(text, dir)
    }

    /** 目录里的 HTML 面板，按 panel.html → ui.html → index.html 的顺序取第一个。 */
    private fun fromHtmlPanel(dir: File): PluginUiSpec? {
        for (name in PANEL_NAMES) {
            val spec = fromHtmlPanelFile(File(dir, name))
            if (spec != null) return spec
        }
        return null
    }

    private fun fromHtmlPanelFile(file: File): PluginUiSpec? {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val html = runCatching { file.readText() }.getOrNull()
        if (html.isNullOrBlank()) return null
        return PluginUiSpec(
            root = PluginUiNode.Html(html),
            widthPolicy = WidthPolicy.Fill,
            // 面板是整页，不是嵌在菜单里的一行；给它滚动，别把长页面裁掉。
            scrollable = true,
        )
    }

    /** 单测里 android.util.Log 是桩会抛，日志永远不该把适配器变成异常源。 */
    private fun warn(msg: String, t: Throwable?) {
        runCatching { AppLog.w(msg, t) }
    }
}
