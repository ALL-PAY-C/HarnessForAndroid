package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.PLUGIN_VIEW_VERSION
import dev.dsh.mobile.pluginapi.PluginViewCode
import dev.dsh.mobile.pluginapi.PluginViewIssue
import dev.dsh.mobile.pluginapi.PluginViewLimits
import dev.dsh.mobile.pluginapi.PluginViewNode
import dev.dsh.mobile.pluginapi.PluginViewRejection
import dev.dsh.mobile.pluginapi.PluginViewSpec
import dev.dsh.mobile.pluginapi.UiBadgeTone
import dev.dsh.mobile.pluginapi.UiButtonStyle
import dev.dsh.mobile.pluginapi.UiColorRole
import dev.dsh.mobile.pluginapi.UiTextEmphasis
import dev.dsh.mobile.pluginapi.WidthPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * 清单里的 `view` 一节 → [PluginViewSpec]，同时把「哪些节点认不出、哪些动作被丢弃」
 * 记成降级清单。**只做数据到数据的翻译**：不 eval、不反射、不加载任何资源。
 *
 * 判定顺序（顺序本身是规格，改顺序等于改行为）：
 * 1. 体积：UTF-8 字节数超过 [PluginViewLimits.maxBytes] → 整体拒收 `view.too_large`。
 * 2. 版本：`v`/`version` 高于 [PLUGIN_VIEW_VERSION] → 拒收 `view.version_unsupported`；
 *    不是 ≥1 的整数 → 拒收 `view.version_invalid`；缺省按本端版本。
 * 3. 结构：节点数 / 深度 / 单容器子节点数 / 字符串长度任一超限 → 整体拒收对应错误码。
 * 4. 语义：认不出的 `type` → 灰占位文本 + `view.unknown_node`（不拒收）；
 *    认不出或未声明的动作 id → 动作置空 + `view.unknown_action`（不拒收）。
 *
 * 「未知属性忽略」不是一条代码分支：解析器只读白名单键（type/text/label/…），
 * 其余键在数据层就没有读入口，插件写 `onClick`/`script` 也不会被执行。
 */
object PluginViewJson {

    /** 字符串入口：体积先按原文算，再交给 JSON 解析。 */
    fun parse(
        text: String?,
        knownActions: Set<String>? = null,
        limits: PluginViewLimits = PluginViewLimits(),
    ): PluginViewResult {
        if (text.isNullOrBlank()) {
            return PluginViewResult(rejection = PluginViewRejection(PluginViewCode.Empty, "/", "清单里没有视图"))
        }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxBytes) {
            return PluginViewResult(
                rejection = PluginViewRejection(
                    PluginViewCode.TooLarge,
                    "/",
                    bytes.toString() + " 字节，上限 " + limits.maxBytes + " 字节",
                ),
            )
        }
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return PluginViewResult(
                rejection = PluginViewRejection(PluginViewCode.NotObject, "/", "解析失败，不是合法 JSON 对象"),
            )
        return parse(json, knownActions, limits, bytes)
    }

    /**
     * 对象入口。[bytes] 传原文字节数（调用方已知时），不传则按对象的紧凑序列化估算 ——
     * 单测与「已经从文件里读成对象」的调用点都走这条。
     */
    fun parse(
        json: JSONObject?,
        knownActions: Set<String>? = null,
        limits: PluginViewLimits = PluginViewLimits(),
        bytes: Int = -1,
    ): PluginViewResult {
        if (json == null) {
            return PluginViewResult(rejection = PluginViewRejection(PluginViewCode.Empty, "/", "清单里没有视图"))
        }
        val size = if (bytes >= 0) bytes else json.toString().toByteArray(Charsets.UTF_8).size
        if (size > limits.maxBytes) {
            return PluginViewResult(
                rejection = PluginViewRejection(
                    PluginViewCode.TooLarge,
                    "/",
                    size.toString() + " 字节，上限 " + limits.maxBytes + " 字节",
                ),
            )
        }
        val version = when (val raw = json.opt("v") ?: json.opt("version")) {
            null -> PLUGIN_VIEW_VERSION
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
                ?: return reject(PluginViewCode.VersionInvalid, "/", "版本号必须是 ≥1 的整数，收到「" + raw + "」")
            else -> return reject(PluginViewCode.VersionInvalid, "/", "版本号必须是 ≥1 的整数")
        }
        if (version > PLUGIN_VIEW_VERSION) {
            return reject(
                PluginViewCode.VersionUnsupported,
                "/",
                "插件写 v" + version + "，本端支持到 v" + PLUGIN_VIEW_VERSION,
            )
        }
        if (version < 1) {
            return reject(PluginViewCode.VersionInvalid, "/", "版本号必须是 ≥1 的整数，收到 " + version)
        }
        val walker = Walker(limits, knownActions)
        val rootJson = json.optJSONObject("root") ?: json
        val root = try {
            walker.node(rootJson, "/root", 1)
        } catch (abort: Abort) {
            return PluginViewResult(rejection = abort.rejection)
        } ?: return reject(PluginViewCode.Empty, "/root", "根节点为空")

        return PluginViewResult(
            spec = PluginViewSpec(
                root = root,
                version = version,
                widthPolicy = when (json.optString("width").lowercase()) {
                    "fill" -> WidthPolicy.Fill
                    "clamp" -> WidthPolicy.Clamp
                    else -> WidthPolicy.Wrap
                },
                scrollable = json.optBoolean("scrollable", false),
                maxHeightDp = json.optInt("maxHeight", 0).takeIf { it > 0 },
            ),
            issues = walker.issues,
        )
    }

    private fun reject(code: String, path: String, detail: String): PluginViewResult =
        PluginViewResult(rejection = PluginViewRejection(code, path, detail))

    /** 超限即整体放弃：用异常跳出递归，比层层回传 null 少一半分支。 */
    private class Abort(val rejection: PluginViewRejection) : RuntimeException(rejection.code)

    private class Walker(
        private val limits: PluginViewLimits,
        private val knownActions: Set<String>?,
    ) {
        val issues = mutableListOf<PluginViewIssue>()
        private var nodes = 0

        fun node(json: JSONObject?, path: String, depth: Int): PluginViewNode? {
            if (json == null) return null
            if (depth > limits.maxDepth) {
                fail(PluginViewCode.TooDeep, path, "层级 " + depth + " 超过上限 " + limits.maxDepth)
            }
            nodes += 1
            if (nodes > limits.maxNodes) {
                fail(PluginViewCode.TooManyNodes, path, "节点数超过上限 " + limits.maxNodes)
            }
            val type = PluginUiJson.typeKey(json.optString("type").ifBlank { json.optString("kind") })
            return when (type) {
                "", "column", "vstack", "vertical", "root", "container", "stack", "box", "div" ->
                    PluginViewNode.Column(childList(json, path, depth), gapOf(json))
                "row", "hstack", "horizontal" -> PluginViewNode.Row(childList(json, path, depth), gapOf(json))
                "list" -> PluginViewNode.List(
                    items = childList(json, path, depth),
                    ordered = json.optBoolean("ordered", false),
                    gap = gapOf(json),
                )
                "text", "label", "paragraph", "heading", "title", "caption" -> PluginViewNode.Text(
                    text = text(json, path, "text", "label", "title"),
                    emphasis = PluginUiJson.emphasisOf(json.optString("emphasis")),
                    colorRole = PluginUiJson.colorOf(json.optString("color").ifBlank { json.optString("colorRole") })
                        ?: UiColorRole.OnSurface,
                )
                "chip", "tag", "badge" -> PluginViewNode.Chip(
                    text = text(json, path, "text", "label"),
                    tone = toneOf(json.optString("tone")),
                )
                "button", "action" -> button(json, path)
                "image", "img", "picture" -> PluginViewNode.Image(
                    source = text(json, path, "src", "source", "uri"),
                    description = text(json, path, "description", "alt", "label"),
                )
                "divider", "hr", "line" -> PluginViewNode.Divider
                else -> {
                    // 未知节点：一行灰占位，别把整块界面丢掉，也绝不按插件写的意思猜着画。
                    issues += PluginViewIssue(PluginViewCode.UnknownNode, path, type)
                    PluginViewNode.Text(
                        text = "（不支持的视图节点：" + type + "）",
                        emphasis = UiTextEmphasis.Caption,
                        colorRole = UiColorRole.OnSurfaceVariant,
                    )
                }
            }
        }

        private fun button(json: JSONObject, path: String): PluginViewNode {
            val label = text(json, path, "label", "text", "title")
            val raw = json.opt("action") ?: json.opt("actionId")
            val id = when (raw) {
                null -> null
                is String -> raw.trim().ifBlank { null }
                // 动作只能是 id 字符串：对象/数组里放不下可执行的东西，一律丢弃。
                else -> {
                    issues += PluginViewIssue(PluginViewCode.UnknownAction, path, "action 必须是动作 id 字符串")
                    null
                }
            }
            val resolved = when {
                id == null -> null
                knownActions == null || id in knownActions -> id
                else -> {
                    issues += PluginViewIssue(PluginViewCode.UnknownAction, path, id)
                    null
                }
            }
            return PluginViewNode.Button(label, resolved, styleOf(json.optString("style")))
        }

        private fun childList(json: JSONObject, path: String, depth: Int): List<PluginViewNode> {
            val key = when {
                json.has("children") -> "children"
                json.has("items") -> "items"
                else -> return emptyList()
            }
            val arr: JSONArray = json.optJSONArray(key) ?: return emptyList()
            if (arr.length() > limits.maxChildren) {
                fail(
                    PluginViewCode.TooManyChildren,
                    path,
                    arr.length().toString() + " 个子节点，上限 " + limits.maxChildren,
                )
            }
            val out = ArrayList<PluginViewNode>(arr.length())
            for (i in 0 until arr.length()) {
                val child = node(arr.optJSONObject(i), path + "/" + key + "/" + i, depth + 1)
                if (child != null) out += child
            }
            return out
        }

        private fun text(json: JSONObject, path: String, vararg keys: String): String {
            var value = ""
            for (key in keys) {
                val candidate = json.optString(key)
                if (candidate.isNotBlank()) {
                    value = candidate
                    break
                }
            }
            if (value.length > limits.maxStringLength) {
                fail(
                    PluginViewCode.StringTooLong,
                    path,
                    "「" + keys.first() + "」" + value.length + " 字，上限 " + limits.maxStringLength,
                )
            }
            return value
        }

        private fun gapOf(json: JSONObject): Int = json.optInt("gap", 8).coerceIn(0, 64)

        private fun styleOf(raw: String): UiButtonStyle = when (raw.lowercase()) {
            "tonal" -> UiButtonStyle.Tonal
            "text", "plain" -> UiButtonStyle.Text
            "outlined", "outline" -> UiButtonStyle.Outlined
            else -> UiButtonStyle.Filled
        }

        private fun toneOf(raw: String): UiBadgeTone = when (raw.lowercase()) {
            "info" -> UiBadgeTone.Info
            "success", "ok" -> UiBadgeTone.Success
            "warning", "warn" -> UiBadgeTone.Warning
            "danger", "error" -> UiBadgeTone.Danger
            else -> UiBadgeTone.Neutral
        }

        private fun fail(code: String, path: String, detail: String): Nothing =
            throw Abort(PluginViewRejection(code, path, detail))
    }
}

/**
 * 一次视图校验的结果。
 *
 * 三态与 [dev.dsh.mobile.pluginapi.Compat] 同一套思路：[spec] 非空就画（可能带降级），
 * [rejection] 非空就一块都不画并把原因显示给用户，两者不会同时有值。
 */
data class PluginViewResult(
    val spec: PluginViewSpec? = null,
    val rejection: PluginViewRejection? = null,
    val issues: List<PluginViewIssue> = emptyList(),
) {
    val ok: Boolean get() = spec != null

    /** 插件详情页/日志直接用的一行。 */
    fun summary(): String = when {
        rejection != null -> "视图未生效：" + rejection.message()
        issues.isEmpty() -> "视图正常（协议 v" + (spec?.version ?: PLUGIN_VIEW_VERSION) + "）"
        else -> "视图已降级（" + issues.size + " 处）：" + issues.joinToString("；") { it.message() }
    }
}
