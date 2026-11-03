package dev.dsh.mobile.core.protocol.tool

import dev.dsh.mobile.core.protocol.ToolOutputSpill
import dev.dsh.mobile.core.protocol.ToolSensitivity
import dev.dsh.mobile.core.protocol.web.WebToolkit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具执行时用得上的东西（T7.1）。
 *
 * 只放拆分前 toolRunner 各分支真正引用到的通道：六个宿主注入执行通道 + 落盘 + 网络工具包。
 * 会话级的 cfg / 确认卡 / 子代理步进回调不在其中 —— 那些是轮次状态，归轮询调度（见 [ToolExecution.TurnLoop]）。
 */
class ToolCtx(
    /** shell：命令 + 通道名（proot / app / shizuku / root）。 */
    val shellExecChannel: suspend (String, String?) -> String,
    /** 原生文件工具：保留 JSON 参数与结果，避免 shell 转义损失。 */
    val fsExec: suspend (String, JsonObject) -> String,
    val channelListExec: suspend () -> String,
    val bgExec: suspend (String, JsonObject) -> String,
    val phoneExec: suspend (JsonObject) -> String,
    val skillExec: suspend (JsonObject) -> String,
    /** 大工具输出落盘（P0-4）：read_output 与执行器共用同一份。 */
    val spill: ToolOutputSpill,
    /** 宿主注入的 shell 说明；声明里的 description 与它就是同一份，不另写一遍。 */
    val shellHelp: () -> String,
    /** 网络工具（web_search / web_fetch / download）：声明与执行体都由它持有，注册表只登记名字与档位。 */
    val web: WebToolkit,
)

/** 一个工具由谁执行。 */
sealed interface ToolExecution {
    /** 交给 [dev.dsh.mobile.core.protocol.ToolExecutor]：单次 30s 超时 + 失败重试 + 大输出落盘。 */
    data class Runner(val handler: suspend (args: JsonObject, ctx: ToolCtx) -> String) : ToolExecution

    /**
     * 由本轮调度自己执行：[why] 写清为什么进不了执行器。
     *
     * todo_write / goal 要写会话投影、要按 sessionId 落状态；subagent 要跑 8 轮工具往返、总预算 180s，
     * 套上 30s 超时 + 重试就是错的。它们的执行体留在 LocalEngineTransport 的轮次循环里，声明仍归这里。
     */
    data class TurnLoop(val why: String) : ToolExecution
}

/**
 * 工具面单一真源（T7.1）：声明（发给模型的 function 对象）、执行（handler）、授权（[sensitivity]）在同一个对象上。
 *
 * 拆分前这三件事各写一遍 —— 声明在 toolsJson()、执行在 toolRunner、授权在 ToolPolicy.verdict 的字符串集合里，
 * 加一个工具要改三处、漏一处就是「声明了但执行不了」或「能执行但不受授权管」。
 */
data class ToolSpec(
    val name: String,
    /** 基准档位。参数会改档的工具（fs_op 的 delete、phone 的复合动作）用 [sensitivityOf] 覆盖。 */
    val sensitivity: ToolSensitivity,
    /** 逐调用档位；默认就是基准档位。 */
    val sensitivityOf: (JsonObject) -> ToolSensitivity = { sensitivity },
    /** 失败不重试。有副作用的动作（手机点击/输入）重试一次 = 再点一次。 */
    val noRetry: Boolean = false,
    /**
     * 声明。是构造器而不是现成 JsonObject：shell 的 description 来自宿主注入的 [ToolCtx.shellHelp]，
     * 只有拿到活的 ctx 才拼得出来。
     */
    val declaration: (ToolCtx) -> JsonObject,
    val execution: ToolExecution,
) {
    /** 执行面：只有 [ToolExecution.Runner] 有。 */
    val handler: (suspend (args: JsonObject, ctx: ToolCtx) -> String)?
        get() = (execution as? ToolExecution.Runner)?.handler
}

/** [JsonPrimitive] 的取值：JsonNull 当缺失处理（拆分前 LocalEngineTransport 的同名私有扩展）。 */
internal fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

