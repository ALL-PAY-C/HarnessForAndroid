package dev.dsh.mobile.core.protocol.tool

import dev.dsh.mobile.core.common.device.CapabilityActions
import dev.dsh.mobile.core.protocol.ToolSensitivity
import dev.dsh.mobile.core.protocol.str
import dev.dsh.mobile.core.protocol.ToolSensitivity.DeviceControl
import dev.dsh.mobile.core.protocol.ToolSensitivity.Irreversible
import dev.dsh.mobile.core.protocol.ToolSensitivity.ReadOnly
import dev.dsh.mobile.core.protocol.ToolSensitivity.ReversibleWrite
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 工具面注册表（T7.1）：一份 [ToolSpec] 列表就是工具面的单一真源。
 *
 * 顺序 = 拆分前 toolsJson() 里 add 的先后（16 条内置）；web 三条（web_search / web_fetch / download）
 * 由 [webSpecs] 追加在末尾，与 [dev.dsh.mobile.core.protocol.web.WebToolkit.functionSpecs] 的次序一致。
 */
object ToolCatalog {

    /** phone 里只读或单步可撤销的动作，直接放行。 */
    private val PHONE_ALLOW = setOf(
        "tree", "readscreen", "screenshot", "currentapp", "apps", "back", "home", "recents", "wait_for",
    )

    /** phone 里能改写别的 App 内容或一步到位做很多事的动作，逐次确认。 */
    private val PHONE_ASK = setOf("type", "sequence", "pinch", "drag")

    /**
     * T4.5：要运行时权限的六个系统能力动作。词表来自 [CapabilityActions]，与 PhoneActions 目录、
     * PhoneBridge 的分派分支共用同一份，不在这里另抄一遍。
     */
    private val PHONE_CAPABILITY: Set<String> = CapabilityActions.PHONE_ACTIONS.toSet()

    private fun phoneSensitivity(action: String?): ToolSensitivity = when {
        action in PHONE_ALLOW -> ReadOnly
        action in PHONE_ASK -> Irreversible
        // 能力动作触碰用户真实数据（联系人/短信/位置），一律按「操控设备」记；
        // 问不问由 ToolPolicy 的三档决定，不再由敏感度决定。
        action in PHONE_CAPABILITY -> DeviceControl
        else -> DeviceControl
    }

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "shell",
            sensitivity = Irreversible,
            declaration = { c -> decl_shell(c) },
            execution = ToolExecution.Runner { args, c -> c.shellExecChannel(args["command"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(), (args["channel"] as? JsonPrimitive)?.contentOrNullSafe()) },
        ),
        ToolSpec(
            name = "todo_write",
            sensitivity = ReversibleWrite,
            declaration = { decl_todo_write() },
            execution = ToolExecution.TurnLoop("要写会话投影 + 引擎 /tool-state，按 sessionId 落状态，见 LocalEngineTransport.todoWriteTool"),
        ),
        ToolSpec(
            name = "goal",
            sensitivity = ReversibleWrite,
            declaration = { decl_goal() },
            execution = ToolExecution.TurnLoop("要读写会话投影的 goal（revision 乐观锁），见 LocalEngineTransport.goalTool"),
        ),
        ToolSpec(
            name = "read_output",
            sensitivity = ReadOnly,
            declaration = { decl_read_output() },
            execution = ToolExecution.Runner { args, c ->
            c.spill.read(
                path = args["path"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                offset = args["offset"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 0,
                limit = args["limit"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: 200,
            )
        },
        ),
        ToolSpec(
            name = "phone",
            sensitivity = DeviceControl, sensitivityOf = { args -> phoneSensitivity(args["action"]?.jsonPrimitive?.contentOrNullSafe()) }, noRetry = true,
            declaration = { decl_phone() },
            execution = ToolExecution.Runner { args, c -> c.phoneExec(args) },
        ),
        ToolSpec(
            name = "phone_tree",
            sensitivity = ReadOnly, noRetry = true,
            declaration = { decl_phone_tree() },
            execution = ToolExecution.Runner { args, c ->
            // phone_tree 复用 phone 通道：toolbridge 的实现细节不进 protocol 的依赖面，
            // 这里只把 action 换成读侧动作名（见 PhoneBridge 的 ACTION_TREE）。
            c.phoneExec(JsonObject(args + ("action" to JsonPrimitive("tree"))))
        },
        ),
        ToolSpec(
            name = "fs_read",
            sensitivity = ReadOnly,
            declaration = { decl_fs_read() },
            execution = ToolExecution.Runner { args, c -> c.fsExec("fs_read", args) },
        ),
        ToolSpec(
            name = "fs_write",
            sensitivity = ReversibleWrite,
            declaration = { decl_fs_write() },
            execution = ToolExecution.Runner { args, c -> c.fsExec("fs_write", args) },
        ),
        ToolSpec(
            name = "fs_list",
            sensitivity = ReadOnly,
            declaration = { decl_fs_list() },
            execution = ToolExecution.Runner { args, c -> c.fsExec("fs_list", args) },
        ),
        ToolSpec(
            name = "fs_op",
            sensitivity = ReversibleWrite, sensitivityOf = { args -> if (args.str("op") == "delete") Irreversible else ReversibleWrite },
            declaration = { decl_fs_op() },
            execution = ToolExecution.Runner { args, c -> c.fsExec("fs_op", args) },
        ),
        ToolSpec(
            name = "channel_list",
            sensitivity = ReadOnly,
            declaration = { decl_channel_list() },
            execution = ToolExecution.Runner { _, c -> c.channelListExec() },
        ),
        ToolSpec(
            name = "run_bg",
            sensitivity = ReversibleWrite,
            declaration = { decl_run_bg() },
            execution = ToolExecution.Runner { args, c -> c.bgExec("run_bg", args) },
        ),
        ToolSpec(
            name = "bg_status",
            sensitivity = ReadOnly,
            declaration = { decl_bg_status() },
            execution = ToolExecution.Runner { args, c -> c.bgExec("bg_status", args) },
        ),
        ToolSpec(
            name = "bg_kill",
            sensitivity = ReversibleWrite,
            declaration = { decl_bg_kill() },
            execution = ToolExecution.Runner { args, c -> c.bgExec("bg_kill", args) },
        ),
        ToolSpec(
            name = "skill",
            sensitivity = ReversibleWrite,
            declaration = { decl_skill() },
            execution = ToolExecution.Runner { args, c -> c.skillExec(args) },
        ),
        ToolSpec(
            name = "subagent",
            sensitivity = Irreversible,
            declaration = { decl_subagent() },
            execution = ToolExecution.TurnLoop("要跑满 8 轮工具往返、总预算 180s，30s 超时 + 重试对它就是错的，见 LocalEngineTransport.runSubagent"),
        ),
    ) + webSpecs()

    private val byName: Map<String, ToolSpec> = specs.associateBy { it.name }

    fun spec(name: String): ToolSpec? = byName[name]

    /**
     * 网络工具（T4.2）。
     *
     * 选「注册」而不是「在声明层追加」：追加只治声明，ToolPolicy 还得再存一份 web_search / web_fetch / download
     * 的名字集合 —— 又回到三处各写一遍。这里只登记名字 + 档位 + 不重试，声明与执行体原样委托给
     * [dev.dsh.mobile.core.protocol.web.WebToolkit]（宿主可注入，单测直接拿它的 toolHandlers() 跑执行器），
     * 不复制第二份 JSON 声明，也就不存在两份会分叉的真源。
     */
    private fun webSpecs(): List<ToolSpec> = listOf(
        webSpec("web_search", ReadOnly),
        webSpec("web_fetch", ReversibleWrite),
        webSpec("download", ReversibleWrite),
    )

    private fun webSpec(name: String, sensitivity: ToolSensitivity): ToolSpec = ToolSpec(
        name = name,
        sensitivity = sensitivity,
        declaration = { c ->
            c.web.functionSpecs().firstOrNull { functionNameOf(it) == name }
                ?: throw IllegalStateException("WebToolkit 没有声明工具 " + name)
        },
        execution = ToolExecution.Runner { args, c ->
            val handler = c.web.toolHandlers()[name]
            if (handler != null) handler(args) else throw IllegalStateException("WebToolkit 没有执行工具 " + name)
        },
    )

    private fun functionNameOf(element: JsonElement): String? =
        ((element as? JsonObject)?.get("function") as? JsonObject)?.get("name")?.jsonPrimitive?.content
}

private fun decl_shell(c: ToolCtx): JsonObject = buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "shell")
                    put("description", c.shellHelp())
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("command") {
                                put("type", "string")
                                put("description", "要执行的命令，例如 ls /sdcard、dumpsys battery、pm list packages")
                            }
                            putJsonObject("channel") {
                                put("type", "string")
                                put("description", "默认 proot；读 /sdcard 用 app 通道")
                                putJsonArray("enum") { add("proot"); add("app"); add("shizuku"); add("root") }
                            }
                            putJsonObject("reason") {
                                put("type", "string")
                                put("description", "为什么要执行这条命令，一句话说明")
                            }
                        }
                        putJsonArray("required") { add("command") }
                    }
                }
}

private fun decl_todo_write(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "todo_write")
                put("description", "更新本轮待办清单：整表替换（不是追加）。每条含 content 与 status，" +
                    "status 只能是 pending / in_progress / completed。同一时刻最多一条 in_progress。")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("todos") {
                            put("type", "array")
                            put("description", "完整待办列表；空数组表示清空")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("content") { put("type", "string"); put("description", "一句话待办") }
                                    putJsonObject("status") {
                                        put("type", "string")
                                        putJsonArray("enum") { add("pending"); add("in_progress"); add("completed") }
                                    }
                                }
                                putJsonArray("required") { add("content"); add("status") }
                            }
                        }
                    }
                    putJsonArray("required") { add("todos") }
                }
            }
}

private fun decl_goal(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "goal")
                put("description", "跨轮次的长期目标：get 读当前目标，create 新建，update 改状态。" +
                    "update 必须带 revision（get 会返回），不匹配说明状态已被别处改过。")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            putJsonArray("enum") { add("get"); add("create"); add("update") }
                        }
                        putJsonObject("objective") { put("type", "string"); put("description", "目标正文（create 必填）") }
                        putJsonObject("phase") {
                            put("type", "string")
                            put("description", "update 用")
                            putJsonArray("enum") { add("active"); add("paused"); add("blocked"); add("complete") }
                        }
                        putJsonObject("max_rounds") { put("type", "integer"); put("description", "最多自动续跑轮数") }
                        putJsonObject("revision") { put("type", "integer"); put("description", "期望的版本号（update 用）") }
                        putJsonObject("blocked_reason") { put("type", "string"); put("description", "phase=blocked 时说明卡在哪") }
                    }
                    putJsonArray("required") { add("action") }
                }
            }
}

private fun decl_read_output(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "read_output")
                put("description", "读回被落盘的大工具输出。path 用工具结果里 locator 给的路径，按行 offset/limit 分段读。")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string"); put("description", "locator 里的 path") }
                        putJsonObject("offset") { put("type", "integer"); put("description", "起始行（0 起）") }
                        putJsonObject("limit") { put("type", "integer"); put("description", "最多返回行数，默认 200") }
                    }
                    putJsonArray("required") { add("path") }
                }
            }
}

private fun decl_phone(): JsonObject = buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "phone")
                    put("description", phoneHelp())
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add("tap"); add("swipe"); add("type"); add("back"); add("home")
                                    add("recents"); add("readscreen"); add("openapp"); add("screenshot")
                                    add("scroll"); add("pinch"); add("drag"); add("wait_for")
                                    add("currentapp"); add("apps"); add("sequence"); add("key")
                                    // T4.5 六类系统能力（与 CapabilityActions.PHONE_ACTIONS 逐字一致）
                                    add("contacts"); add("sms_list"); add("sms_send")
                                    add("dial"); add("location"); add("audio_record")
                                }
                                put(
                                    "description",
                                    "动作：tap 点击 / swipe 滑动 / type 输入 / back 返回 / home 主页 / recents 最近任务 / " +
                                        "readscreen 读屏 / openapp 开应用 / screenshot 截图 / " +
                                        "scroll 滚可滚容器 / pinch 双指缩放 / drag 拖拽 / wait_for 等条件成立 / " +
                                        "currentapp 当前应用 / apps 已装可启动应用 / sequence 批量步骤 / key 系统键或输入框键 / " +
                                        "contacts 联系人 / sms_list 读短信 / sms_send 发短信 / dial 拨号 / location 定位 / audio_record 录音",
                                )
                            }
                            putJsonObject("fx") { put("type", "number"); put("description", "起点横坐标（分数 0-1，相对屏幕宽，优先于 x）") }
                            putJsonObject("fy") { put("type", "number"); put("description", "起点纵坐标（分数 0-1，相对屏幕高，优先于 y）") }
                            putJsonObject("x") { put("type", "number"); put("description", "起点横坐标（绝对像素）") }
                            putJsonObject("y") { put("type", "number"); put("description", "起点纵坐标（绝对像素）") }
                            putJsonObject("fx2") { put("type", "number"); put("description", "终点横坐标（分数 0-1，swipe 用）") }
                            putJsonObject("fy2") { put("type", "number"); put("description", "终点纵坐标（分数 0-1，swipe 用）") }
                            putJsonObject("x2") { put("type", "number"); put("description", "终点横坐标（绝对像素，swipe 用）") }
                            putJsonObject("y2") { put("type", "number"); put("description", "终点纵坐标（绝对像素，swipe 用）") }
                            putJsonObject("text") { put("type", "string"); put("description", "要输入的文本（type 用）") }
                            putJsonObject("package") { put("type", "string"); put("description", "应用包名（openapp 用）") }
                            putJsonObject("query") { put("type", "string"); put("description", "按文本/描述查找节点并点击（tap 用，可选）") }
                            putJsonObject("durationMs") { put("type", "number"); put("description", "手势时长毫秒（tap 长按 / swipe 滑动时长）") }
                            putJsonObject("maxItems") { put("type", "number"); put("description", "读屏最多返回条目数（readscreen 用，默认 120）") }
                            putJsonObject("nodeId") {
                                put("type", "string")
                                put("description", "节点身份 \"<snapshotId>:<seq>\"（来自 phone_tree）。跨代失效回 E_STALE_SNAPSHOT 且不执行")
                            }
                            putJsonObject("target") {
                                put("type", "object")
                                put("description", "选择器对象：text/textContains/desc/viewId/viewIdSuffix/className/package/index/clickable/editable/scrollable（字段间 AND）。多命中回 E_NODE_AMBIGUOUS，加 index 或换更精确条件")
                            }
                            putJsonObject("index") {
                                put("type", "number")
                                put("description", "选择器多命中时取第几个（0 起）")
                            }
                            putJsonObject("mode") { put("type", "string"); put("description", "type 的模式：set 覆盖 / append 追加 / clear 清空（默认 set）") }
                            putJsonObject("perChar") { put("type", "boolean"); put("description", "type 逐字写入（默认 false）") }
                            putJsonObject("intervalMs") { put("type", "number"); put("description", "逐字间隔毫秒（perChar 用，20-1000，默认 80）") }
                            putJsonObject("key") { put("type", "string"); put("description", "back/home/recents/enter/delete/tab（key 动作）") }
                            putJsonObject("times") { put("type", "number"); put("description", "重复次数（key/scroll 用，1-20，默认 1）") }
                            putJsonObject("direction") { put("type", "string"); put("description", "scroll 方向：forward 往下 / backward 往上") }
                            putJsonObject("verify") { put("type", "boolean"); put("description", "scroll 是否用界面指纹校验真的滚了（默认 true）") }
                            putJsonObject("until") { put("type", "object"); put("description", "scroll 的目标选择器：命中即停（stoppedReason=until）") }
                            putJsonObject("cond") {
                                put("type", "object")
                                put("description", "wait_for 条件：type=node_present/node_absent（带 selector）、text_present（带 textContains）、package_is（带 package）、generation_changed")
                            }
                            putJsonObject("timeoutMs") { put("type", "number"); put("description", "wait_for 超时毫秒（100-15000，默认 3000），超时回 E_WAIT_TIMEOUT") }
                            putJsonObject("pollMs") { put("type", "number"); put("description", "wait_for 轮询间隔毫秒（100-2000，默认 250）") }
                            putJsonObject("scale") { put("type", "number"); put("description", "pinch 比例：<1 缩小、>1 放大（也可直接给 fromRadius/toRadius）") }
                            putJsonObject("fromRadius") { put("type", "number"); put("description", "pinch 起始半径像素（≥20）") }
                            putJsonObject("toRadius") { put("type", "number"); put("description", "pinch 结束半径像素（≥20）") }
                            putJsonObject("cx") { put("type", "number"); put("description", "pinch 中心横坐标（绝对像素，默认屏幕中心）") }
                            putJsonObject("cy") { put("type", "number"); put("description", "pinch 中心纵坐标（绝对像素，默认屏幕中心）") }
                            putJsonObject("startTarget") { put("type", "object"); put("description", "swipe 起点选择器或 nodeId 串") }
                            putJsonObject("endTarget") { put("type", "object"); put("description", "swipe 终点选择器或 nodeId 串") }
                            putJsonObject("fromX") { put("type", "number"); put("description", "drag 起点横坐标（或 x）") }
                            putJsonObject("fromY") { put("type", "number"); put("description", "drag 起点纵坐标（或 y）") }
                            putJsonObject("toX") { put("type", "number"); put("description", "drag 终点横坐标（或给 dx 位移）") }
                            putJsonObject("toY") { put("type", "number"); put("description", "drag 终点纵坐标（或给 dy 位移）") }
                            putJsonObject("dx") { put("type", "number"); put("description", "drag 横向位移（toX 缺省时用）") }
                            putJsonObject("dy") { put("type", "number"); put("description", "drag 纵向位移（toY 缺省时用）") }
                            putJsonObject("holdMs") { put("type", "number"); put("description", "drag 按下保持毫秒（0-2000，默认 500）") }
                            putJsonObject("steps") {
                                put("type", "array")
                                put("description", "sequence 的步骤数组（≤20 步）：{action, args?, waitAfterMs?}。可用 tap/swipe/scroll/type/key/pinch/drag/wait_for/openapp/back/home/recents；动作参数也可直接写在步骤里（不必包一层 args）")
                            }
                            putJsonObject("stopOnError") { put("type", "boolean"); put("description", "sequence 单步失败是否立即停（默认 true，不回滚）") }
                            putJsonObject("dryRun") { put("type", "boolean"); put("description", "sequence 只校验参数不执行（默认 false）") }
                            putJsonObject("limit") { put("type", "number"); put("description", "apps 每页条数（1-500，默认 120）") }
                            putJsonObject("offset") { put("type", "number"); put("description", "apps 分页起始下标（默认 0）") }
                            putJsonObject("includeSystem") { put("type", "boolean"); put("description", "apps 是否包含系统应用（默认 false）") }
                            putJsonObject("maxDepth") { put("type", "number"); put("description", "动作侧读树的深度上限（默认 6，与 phone_tree 同义）") }
                            putJsonObject("maxNodes") { put("type", "number"); put("description", "动作侧读树的节点上限（默认 400，与 phone_tree 同义）") }
                            putJsonObject("includeInvisible") { put("type", "boolean"); put("description", "动作侧是否包含不可见节点（默认 false）") }
                            putJsonObject("onlyClickable") { put("type", "boolean"); put("description", "动作侧只保留可点节点（默认 false）") }
                            putJsonObject("filter") { put("type", "object"); put("description", "动作侧读树的过滤选择器（与 phone_tree 的 filter 同义，保证 seq 一致）") }
                        }
                        putJsonArray("required") { add("action") }
                    }
                }
}

private fun decl_phone_tree(): JsonObject = buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "phone_tree")
                    put("description", phoneTreeHelp())
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("maxDepth") { put("type", "integer"); put("description", "最大深度，默认 6（越界夹到 1..64）") }
                            putJsonObject("maxNodes") { put("type", "integer"); put("description", "最多返回节点数，默认 400（越界夹到 1..2000）") }
                            putJsonObject("includeInvisible") { put("type", "boolean"); put("description", "是否包含不可见节点，默认 false") }
                            putJsonObject("excludeOwnPackage") { put("type", "boolean"); put("description", "是否剔除手机控制自身的浮层/窗口，默认 true") }
                            putJsonObject("package") { put("type", "string"); put("description", "只看该包名的窗口（可选）") }
                            putJsonObject("onlyClickable") { put("type", "boolean"); put("description", "只返回可点击节点，默认 false") }
                            putJsonObject("filter") {
                                put("type", "object")
                                put("description", "节点过滤选择器，字段之间是 AND，缺省字段不参与匹配")
                                putJsonObject("properties") {
                                    putJsonObject("text") { put("type", "string"); put("description", "text 全等") }
                                    putJsonObject("textContains") { put("type", "string"); put("description", "text 包含（忽略大小写）") }
                                    putJsonObject("desc") { put("type", "string"); put("description", "contentDescription 全等") }
                                    putJsonObject("viewId") { put("type", "string"); put("description", "完整 resource-id，如 com.android.settings:id/search") }
                                    putJsonObject("viewIdSuffix") { put("type", "string"); put("description", "只写 id 段（冒号后那截）") }
                                    putJsonObject("className") { put("type", "string"); put("description", "控件类名全等") }
                                    putJsonObject("package") { put("type", "string"); put("description", "节点所属包名") }
                                    putJsonObject("index") { put("type", "integer"); put("description", "命中集合里的第几个（0 起）") }
                                    putJsonObject("clickable") { put("type", "boolean"); put("description", "要求可点击") }
                                    putJsonObject("editable") { put("type", "boolean"); put("description", "要求可输入") }
                                    putJsonObject("scrollable") { put("type", "boolean"); put("description", "要求可滚动") }
                                }
                            }
                        }
                    }
                }
}

private fun decl_fs_read(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "fs_read")
                put("description", "读取原生文件")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("offset") { put("type", "integer") }
                        putJsonObject("limit") { put("type", "integer") }
                    }
                    putJsonArray("required") { add("path") }
                }
            }
}

private fun decl_fs_write(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "fs_write")
                put("description", "写入原生文件")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("content") { put("type", "string") }
                        putJsonObject("append") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("path"); add("content") }
                }
            }
}

private fun decl_fs_list(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "fs_list")
                put("description", "列出原生目录")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("recursive") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("path") }
                }
            }
}

private fun decl_fs_op(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "fs_op")
                put("description", "操作原生文件")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("op") { put("type", "string"); putJsonArray("enum") { add("move"); add("copy"); add("delete"); add("mkdir"); add("stat") } }
                        putJsonObject("src") { put("type", "string") }
                        putJsonObject("dst") { put("type", "string") }
                    }
                    putJsonArray("required") { add("op"); add("src") }
                }
            }
}

private fun decl_channel_list(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "channel_list")
                put("description", "列出 shell 通道与可用状态")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {

                    }
                    putJsonArray("required") {  }
                }
            }
}

private fun decl_run_bg(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "run_bg")
                put("description", "启动后台命令")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("command") { put("type", "string") }
                        putJsonObject("logfile") { put("type", "string") }
                    }
                    putJsonArray("required") { add("command") }
                }
            }
}

private fun decl_bg_status(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "bg_status")
                put("description", "查询后台进程和日志")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pid") { put("type", "integer") }
                        putJsonObject("logfile") { put("type", "string") }
                    }
                    putJsonArray("required") { add("pid") }
                }
            }
}

private fun decl_bg_kill(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "bg_kill")
                put("description", "终止后台进程")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("pid") { put("type", "integer") }
                    }
                    putJsonArray("required") { add("pid") }
                }
            }
}

private fun decl_skill(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "skill")
                put("description", "读取一个已安装技能的完整说明（SKILL.md）。当任务与某个技能描述匹配时，先调用本工具读取再按技能执行。")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string"); put("description", "技能名") }
                    }
                    putJsonArray("required") { add("name") }
                }
            }
}

private fun decl_subagent(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "subagent")
                put("description", subagentHelp())
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("prompt") {
                            put("type", "string")
                            put(
                                "description",
                                "要委派的完整任务：目标、要看的东西、期望的输出形态。子代理看不到当前对话，别写「上面那个文件」。",
                            )
                        }
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "短标签（2-6 字），只用于进度显示，例如「查日志」")
                        }
                        putJsonObject("model") {
                            put("type", "string")
                            put("description", "可选：换一个模型跑这个子任务；留空继承当前模型")
                        }
                        putJsonObject("depth") {
                            put("type", "integer")
                            put("description", "当前调用者所在层级（主代理 0，第一层子代理 1）；省略按运行时深度算，" +
                                "超过嵌套上限的派发会被拒并回「子代理最多嵌套 N 层」")
                        }
                    }
                    putJsonArray("required") { add("prompt") }
                }
            }
}

    private fun phoneHelp(): String = buildString {
        append("phone 工具：直接操控手机（无需 root/ADB，需先在 设置→无障碍 开启「DSH Mobile 手机控制」）。")
        append("动作 action：tap（点击，用 fx/fy 分数坐标最稳，或 x/y 像素；带 query 按文本点；带 durationMs 变长按）")
        append("、swipe（滑动，起点 fx/fy 或 x/y，终点 fx2/fy2 或 x2/y2，durationMs 控制快慢）")
        append("、type（往聚焦输入框写字，参数 text）、back/home/recents（系统键）")
        append("、readscreen（读当前屏幕文本，紧凑列表，maxItems 限条目）")
        append("、openapp（package 包名或 query 应用名模糊匹配）、screenshot（Android 11+ 存本地文件）。")
        append("坐标优先用 fx/fy（0-1 相对屏幕），模型看的是缩放截图，绝对像素容易点偏。")
        append("读屏输出每行 text|desc|cx,cy|clickable|class。")
        append("结构化读屏（viewId/bounds/节点身份/快照代次）用 phone_tree 工具。")
        append("定位统一用 target（选择器对象）或 nodeId（\"<snapshotId>:<seq>\"，来自 phone_tree）：")
        append("nodeId 跨代回 E_STALE_SNAPSHOT、选择器多命中回 E_NODE_AMBIGUOUS，两者都不派发任何动作。")
        append("命中节点自身不可点会自动上溯最近可点祖先（列表行靠这个才点得动）。")
        append("新动作：scroll（滚 scrollable=true 容器，direction/times/until/verify，advanced 用界面指纹判）")
        append("、wait_for（cond.type=node_present/node_absent/text_present/package_is/generation_changed，timeoutMs 默认 3000，超时回 E_WAIT_TIMEOUT）")
        append("、pinch（center 或 cx/cy + scale 或 fromRadius/toRadius）、drag（fromX/fromY→toX/toY 或 dx/dy，holdMs）")
        append("、key（enter/delete/tab 打在聚焦输入框；back/home/recents 走系统键）、currentapp（当前前台应用）")
        append("、apps（已装可启动应用，query/limit/offset 分页）、sequence（steps 一次下发，单步失败带步骤号即停）")
        append("、type 另支持 target + mode=set/append/clear + perChar 逐字（intervalMs）。")
        append("六类系统能力动作（不依赖无障碍，需运行时权限，缺权限回 E_PERMISSION_DENIED + needsPermission）：")
        append("contacts（query 查联系人）、sms_list（读收件箱）、sms_send（number+text）、dial（number 直接拨出）、")
        append("location（最近已知位置）、audio_record（durationMs 录音）。这三档授权在 设置→手机能力授权 里配。")
        append("新动作外层恒 ok=true，真实成败与错误码在内层 {ok,code,message,retryable,effectLanded}；手机动作不重试。")
    }

    /**
     * phone_tree 的说明。
     *
     * 与 [phoneHelp] 分开写是因为它讲的是「读」而不是「动作」；T4.3 要把这两段与
     * PhoneBridge.describeForModel() 合并成单一真源，届时一起搬。
     */
    private fun phoneTreeHelp(): String = buildString {
        append("phone_tree：把当前界面读成结构化节点树（需先在 设置→无障碍 开启「DSH Mobile 手机控制」）。")
        append("返回 snapshotId 与扁平 nodes[]，每个节点带 nodeId（\"<snapshotId>:<seq>\"）、parentId、depth、")
        append("className/package/text/desc/viewId/bounds{left,top,right,bottom}/clickable/editable/scrollable/")
        append("checkable/checked/selected/enabled/focused/visible，外加 screen{width,height,density}、")
        append("foreground{package,activity,sinceMs}、nodeCount、truncated + truncatedReason、changedDuringRead、elapsedMs。")
        append("参数：maxDepth 默认 6、maxNodes 默认 400、includeInvisible 默认 false、onlyClickable 默认 false、")
        append("package 只看该包名；filter 是选择器对象（text/textContains/desc/viewId/viewIdSuffix/className/package/")
        append("index/clickable/editable/scrollable，字段间 AND），filter 无命中返回 E_NODE_NOT_FOUND。")
        append("节点身份带代次：界面变了以后旧 nodeId 作废，重读一次拿新 snapshotId；changedDuringRead=true 表示遍历期间界面又变了。")
        append("只读，不改变界面；要点击/输入用 phone 工具。")
    }

    private fun subagentHelp(): String = buildString {
        append("subagent 工具：把独立、可并行的子任务交给子代理。")
        append("子代理有自己的上下文和工具（shell / 文件 / phone），只把结论回给你，")
        append("不占用也不污染主对话历史——适合「同时查三件事」「先读完一大堆代码再给我结论」。")
        append("它看不到当前对话，prompt 必须自带全部背景，别写「上面那个文件」。")
        append("嵌套有深度上限（默认 2 层，主代理算第 0 层）：第 2 层还能再派，第 3 层会被拒并回「子代理最多嵌套 2 层」。")
        append("互不相关的子任务就同一轮里发多个 subagent：依次执行、各自回结论。")
        append("要把活拆给执行池：在结论里显式列出来——\"- 任务：<标题>｜<说明>\" 每行一条，")
        append("或一段 [{\"title\":\"..\",\"detail\":\"..\"}] JSON 数组；父会话会把它投进执行池并记下父任务 id。")
    }

/**
 * 绑定注入的通道后，把 [ToolCatalog] 摊平成执行器与工具表要的形状。
 *
 * 拆分前这三样是 toolsJson()、toolRunner 的 Map、ToolPolicy 的字符串集合；
 * 现在都由同一份 [ToolSpec] 派生，加工具只改 ToolCatalog。
 */
class ToolRegistry(
    private val ctx: ToolCtx,
    /**
     * 宿主注入的额外工具源（T9.10 P3：MCP 服务器）。
     *
     * 是函数而不是列表：工具表每轮都可能变（服务器连上/断开/改了配置），
     * 取自 snapshot 才「配置变更即生效，不用重启 transport」。
     */
    private val extra: () -> List<ToolSpec> = { emptyList() },
) {

    /** 内置 16 条 + web 三条 + 注入的额外工具（顺序 = 声明顺序）。 */
    val specs: List<ToolSpec> get() = ToolCatalog.specs + extra()

    /** 声明清单：保持拆分前 toolsJson() 的顺序，web 三条在末尾。 */
    fun allDeclarations(): List<JsonObject> = specs.map { it.declaration(ctx) }

    /** 发给模型的工具表：[dev.dsh.mobile.core.protocol.LocalEngineTransport.toolsJson] 现在就是它。 */
    fun declarations(): JsonArray = JsonArray(allDeclarations())

    fun get(name: String): ToolSpec? =
        ToolCatalog.spec(name) ?: extra().firstOrNull { it.name == name }

    fun names(): List<String> = specs.map { it.name }

    /**
     * 执行面：[dev.dsh.mobile.core.protocol.ToolExecutor] 的入参。
     *
     * 排序只为让「未知工具」报错里那句「已注册：…」与拆分前逐字一致（拆分前 Map 的构造顺序；
     * [EXECUTOR_ORDER] 里查不到的名字排到末尾，不会漏工具）。
     */
    fun handlers(): Map<String, suspend (JsonObject) -> String> {
        val out = LinkedHashMap<String, suspend (JsonObject) -> String>()
        val runnable = specs.mapNotNull { spec ->
            val handler = spec.handler ?: return@mapNotNull null
            val bound: suspend (JsonObject) -> String = { args -> handler(args, ctx) }
            spec.name to bound
        }.sortedBy { (name, _) -> EXECUTOR_ORDER.indexOf(name).let { if (it < 0) Int.MAX_VALUE else it } }
        runnable.forEach { (name, handler) -> out[name] = handler }
        return out
    }

    /** 失败不重试的名单（有副作用的动作：重试一次 = 把点击再打一遍）。 */
    fun noRetryNames(): Set<String> = specs.filter { it.noRetry }.map { it.name }.toSet()

    private companion object {
        /** 拆分前 ToolExecutor 构造时 Map 的 key 顺序，见 T7.1 报告。 */
        val EXECUTOR_ORDER = listOf(
            "shell", "fs_read", "fs_write", "fs_list", "fs_op", "channel_list",
            "run_bg", "bg_status", "bg_kill", "phone", "phone_tree", "skill", "read_output",
            "web_search", "web_fetch", "download",
        )
    }
}
