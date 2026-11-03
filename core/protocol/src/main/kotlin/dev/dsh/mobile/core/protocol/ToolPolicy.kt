package dev.dsh.mobile.core.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具敏感度分档（T6.1b）。
 *
 * 分档只描述「这个动作会不会动到用户的真实数据或别人的应用」，不决定拦不拦 ——
 * 拦不拦由 [ToolPolicy.verdict] 按档位 + 全局自动批准设置决定，两件事分开才好审计。
 */
enum class ToolSensitivity {
    /** 只读：读文件、看树、查后台状态、web 检索。 */
    ReadOnly,

    /** 可回滚写：写文件、建目录、装技能、改待办。 */
    ReversibleWrite,

    /** 不可逆：删文件、跑子代理、多步序列（一步错后面全错）。 */
    Irreversible,

    /** 操控设备：点击/输入/缩放，会影响别的 App 的界面状态。 */
    DeviceControl,
}

/**
 * 一次调用的授权裁决。
 *
 * 名字刻意不叫 ToolVerdict —— 那个名字已被 [ToolLoopGuard] 占用（防抖裁决 Execute/Reuse/Loop），
 * 两件事（要不要拦重复调用 vs 要不要问用户）混在一个名字下迟早会串。
 */
sealed interface ToolAuth {
    data object Allow : ToolAuth
    data class Deny(val reason: String) : ToolAuth
    data class Ask(val title: String) : ToolAuth
}

/**
 * 工具授权判定表。
 *
 * 以前只有 shell 走确认（[LocalEngineTransport.CONFIRM_PATTERNS] 字符串包含匹配），
 * phone 这类「不是 shell 命令」的工具完全不受逐调用授权 —— 模型想点哪就点哪。
 * 这里把判据搬到工具名 + 参数上，phone 的复合动作（sequence/pinch/drag/输入）默认要问。
 */
object ToolPolicy {
    /** phone 里只读或单步可撤销的动作，直接放行。 */
    private val PHONE_ALLOW = setOf(
        "tree", "readscreen", "screenshot", "currentapp", "apps", "back", "home", "recents", "wait_for",
    )

    /** phone 里能改写别的 App 内容或一步到位做很多事的动作，逐次确认。 */
    private val PHONE_ASK = setOf("type", "sequence", "pinch", "drag")

    private val READ_ONLY_TOOLS = setOf(
        "fs_read", "fs_list", "read_output", "channel_list", "bg_status", "phone_tree", "web_search",
    )

    private val REVERSIBLE_WRITE_TOOLS = setOf("fs_write", "todo_write", "goal", "skill", "download", "web_fetch")

    /**
     * 一次调用的敏感度。未知工具一律按 [ToolSensitivity.DeviceControl] 之外的保守档处理：
     * 归到 ReversibleWrite（不是要拦它，是不敢声称它只读）。
     */
    fun sensitivity(name: String, args: JsonObject): ToolSensitivity = when (name) {
        in READ_ONLY_TOOLS -> ToolSensitivity.ReadOnly
        in REVERSIBLE_WRITE_TOOLS -> ToolSensitivity.ReversibleWrite
        "shell" -> ToolSensitivity.Irreversible
        "subagent" -> ToolSensitivity.Irreversible
        "fs_op" -> if (toolArg(args, "op") == "delete") ToolSensitivity.Irreversible else ToolSensitivity.ReversibleWrite
        "run_bg" -> ToolSensitivity.ReversibleWrite
        "bg_kill" -> ToolSensitivity.ReversibleWrite
        "phone" -> phoneSensitivity(toolArg(args, "action"))
        else -> ToolSensitivity.ReversibleWrite
    }

    private fun phoneSensitivity(action: String?): ToolSensitivity = when (action) {
        in PHONE_ALLOW -> ToolSensitivity.ReadOnly
        in PHONE_ASK -> ToolSensitivity.Irreversible
        else -> ToolSensitivity.DeviceControl
    }

    /**
     * 判定。规则很少，刻意少：档位不可逆 + 复合动作才问，其余放行。
     *
     * shell 不走这里 —— 它的判据是命令文本（[LocalEngineTransport.needsConfirmation]），
     * 同一条规则两处实现会分叉，所以 shell 仍由调用点先判。
     */
    fun verdict(name: String, args: JsonObject): ToolAuth {
        val sensitivity = sensitivity(name, args)
        if (sensitivity != ToolSensitivity.Irreversible) return ToolAuth.Allow
        val title = when (name) {
            "phone" -> "确认执行手机动作：" + (toolArg(args, "action") ?: "?")
            "fs_op" -> "确认删除：" + (toolArg(args, "src") ?: "?")
            "subagent" -> "确认派发子代理"
            else -> "确认执行：" + name
        }
        return ToolAuth.Ask(title)
    }

}

private fun toolArg(obj: JsonObject, key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
