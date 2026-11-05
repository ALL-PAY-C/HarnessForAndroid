package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.device.CapabilityActions
import dev.dsh.mobile.core.common.device.CapabilityTier
import dev.dsh.mobile.core.common.device.CapabilityTiers
import dev.dsh.mobile.core.common.device.DeviceCapability
import dev.dsh.mobile.core.protocol.tool.ToolCatalog
import dev.dsh.mobile.core.protocol.tool.ToolSpec
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
    /**
     * [capability] 非空表示这次问的是「六类系统能力」的档位（T4.5）：用户点允许后
     * 调用点按 Session 档把这项能力记进本会话，后续同一会话不再问。
     */
    data class Ask(val title: String, val capability: DeviceCapability? = null) : ToolAuth
}

/**
 * 工具授权判定表。
 *
 * 以前只有 shell 走确认（[LocalEngineTransport.CONFIRM_PATTERNS] 字符串包含匹配），
 * phone 这类「不是 shell 命令」的工具完全不受逐调用授权 —— 模型想点哪就点哪。
 * 这里把判据搬到工具名 + 参数上，phone 的复合动作（sequence/pinch/drag/输入）默认要问。
 *
 * T7.1：档位不再在这张表里另抄一份工具名单，改成读 [ToolCatalog] 的同一份 ToolSpec ——
 * 声明、执行、授权三处各写一遍名单的时代结束（以前加一个工具要改三处，漏一处就是
 * 「声明了但执行不了」或「能执行但不受授权管」）。
 */
object ToolPolicy {

    /**
     * 一次调用的敏感度：查 ToolSpec 的逐调用档位。
     *
     * 未注册的名字归 [ToolSensitivity.ReversibleWrite]（不是要拦它，是不敢声称它只读）；
     * 与拆分前 else 分支一致。
     */
    fun sensitivity(
        name: String,
        args: JsonObject,
        /** 解析器：默认只认内置目录；宿主传 ToolRegistry::get，注入的 MCP 工具才拿得到自己的档位。 */
        resolve: (String) -> ToolSpec? = ToolCatalog::spec,
    ): ToolSensitivity =
        resolve(name)?.sensitivityOf(args) ?: ToolSensitivity.ReversibleWrite

    /**
     * 判定。规则很少，刻意少：档位不可逆 + 复合动作才问，其余放行。
     *
     * shell 不走这里 —— 它的判据是命令文本（[LocalEngineTransport.needsConfirmation]），
     * 同一条规则两处实现会分叉，所以 shell 仍由调用点先判。
     */
    fun verdict(
        name: String,
        args: JsonObject,
        capabilityTiers: String = "",
        sessionGranted: Set<DeviceCapability> = emptySet(),
        resolve: (String) -> ToolSpec? = ToolCatalog::spec,
    ): ToolAuth {
        // T4.5：六类系统能力先按三档判，判完就不再走「不可逆才问」的老规则。
        capabilityVerdict(name, args, capabilityTiers, sessionGranted)?.let { return it }
        val sensitivity = sensitivity(name, args, resolve)
        if (sensitivity != ToolSensitivity.Irreversible) return ToolAuth.Allow
        val title = when (name) {
            "phone" -> "Confirm phone action: " + (toolArg(args, "action") ?: "?")
            "fs_op" -> "Confirm delete: " + (toolArg(args, "src") ?: "?")
            "subagent" -> "Confirm subagent dispatch"
            else -> "Confirm run: " + name
        }
        return ToolAuth.Ask(title)
    }

    /**
     * 六类系统能力的三档裁决；不是能力动作就返回 null，交回老规则。
     *
     * Always 直接放行；Session 在本会话确认过就放行；Ask（也是默认档）每次都问。
     * 档位串解析不出来时 [CapabilityTiers] 回落 Ask —— 配置坏了只会多问一次，不会静默放行。
     */
    private fun capabilityVerdict(
        name: String,
        args: JsonObject,
        capabilityTiers: String,
        sessionGranted: Set<DeviceCapability>,
    ): ToolAuth? {
        if (name != "phone") return null
        val action = toolArg(args, "action") ?: return null
        val capability = CapabilityActions.capabilityOf(action) ?: return null
        // 调用点的确认卡会自己加「确认执行：」前缀，这里不再重复写「确认」。
        val ask = ToolAuth.Ask("Use capability '" + capability.id + "': " + action, capability)
        return when (CapabilityTiers.tierOf(capabilityTiers, capability)) {
            CapabilityTier.Always -> ToolAuth.Allow
            CapabilityTier.Session -> if (capability in sessionGranted) ToolAuth.Allow else ask
            CapabilityTier.Ask -> ask
        }
    }

}

private fun toolArg(obj: JsonObject, key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
