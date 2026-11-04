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
            execution = ToolExecution.TurnLoop("Writes the session projection + engine /tool-state, keyed by sessionId; see LocalEngineTransport.todoWriteTool"),
        ),
        ToolSpec(
            name = "goal",
            sensitivity = ReversibleWrite,
            declaration = { decl_goal() },
            execution = ToolExecution.TurnLoop("Reads/writes the session projection goal (revision optimistic lock); see LocalEngineTransport.goalTool"),
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
            execution = ToolExecution.TurnLoop("Needs up to 8 tool round-trips with a 180s total budget; a 30s timeout plus retry is wrong for it; see LocalEngineTransport.runSubagent"),
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
                ?: throw IllegalStateException("WebToolkit has no declaration for " + name)
        },
        execution = ToolExecution.Runner { args, c ->
            val handler = c.web.toolHandlers()[name]
            if (handler != null) handler(args) else throw IllegalStateException("WebToolkit has no handler for " + name)
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
                                put("description", "Command to run, e.g. ls /sdcard, dumpsys battery, pm list packages")
                            }
                            putJsonObject("channel") {
                                put("type", "string")
                                put("description", "Defaults to proot; use the app channel to read /sdcard")
                                putJsonArray("enum") { add("proot"); add("app"); add("shizuku"); add("root") }
                            }
                            putJsonObject("reason") {
                                put("type", "string")
                                put("description", "Why this command is needed, in one sentence")
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
                put("description", "Update this turn's todo list: full replacement, not append. Each item has content and status," +
                    "status must be pending / in_progress / completed. At most one item is in_progress at a time.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("todos") {
                            put("type", "array")
                            put("description", "The complete todo list; an empty array clears it")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("content") { put("type", "string"); put("description", "One-line todo") }
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
                put("description", "Long-running goal across turns: get reads the current goal, create starts one, update changes its state." +
                    "update must carry revision (get returns it); a mismatch means the state was changed elsewhere.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            putJsonArray("enum") { add("get"); add("create"); add("update") }
                        }
                        putJsonObject("objective") { put("type", "string"); put("description", "Goal text (required for create)") }
                        putJsonObject("phase") {
                            put("type", "string")
                            put("description", "Goal phase (used by update)")
                            putJsonArray("enum") { add("active"); add("paused"); add("blocked"); add("complete") }
                        }
                        putJsonObject("max_rounds") { put("type", "integer"); put("description", "Maximum automatic continuation rounds") }
                        putJsonObject("revision") { put("type", "integer"); put("description", "Expected revision (used by update)") }
                        putJsonObject("blocked_reason") { put("type", "string"); put("description", "When phase=blocked, explain what is blocking") }
                    }
                    putJsonArray("required") { add("action") }
                }
            }
}

private fun decl_read_output(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "read_output")
                put("description", "Read back a large tool output that was spilled to disk. Use the path from the locator in the tool result; read it in offset/limit line ranges.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string"); put("description", "path from the locator in the tool result") }
                        putJsonObject("offset") { put("type", "integer"); put("description", "Start line (0-based)") }
                        putJsonObject("limit") { put("type", "integer"); put("description", "Maximum lines returned, default 200") }
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
                                    "action: tap = click / swipe = swipe / type = type text / back = back / home = home / recents = recent tasks / " +
                                        "readscreen = read screen / openapp = open app / screenshot = screenshot / " +
                                        "scroll = scroll a container / pinch = two-finger zoom / drag = drag / wait_for = wait for a condition / " +
                                        "currentapp = current app / apps = installed launchable apps / sequence = batched steps / key = system or input keys / " +
                                        "contacts = contacts / sms_list = read SMS / sms_send = send SMS / dial = place call / location = locate / audio_record = record audio",
                                )
                            }
                            putJsonObject("fx") { put("type", "number"); put("description", "Start X (fraction 0-1 relative to screen width, takes precedence over x)") }
                            putJsonObject("fy") { put("type", "number"); put("description", "Start Y (fraction 0-1 relative to screen height, takes precedence over y)") }
                            putJsonObject("x") { put("type", "number"); put("description", "Start X (absolute pixels)") }
                            putJsonObject("y") { put("type", "number"); put("description", "Start Y (absolute pixels)") }
                            putJsonObject("fx2") { put("type", "number"); put("description", "End X (fraction 0-1, swipe)") }
                            putJsonObject("fy2") { put("type", "number"); put("description", "End Y (fraction 0-1, swipe)") }
                            putJsonObject("x2") { put("type", "number"); put("description", "End X (absolute pixels, swipe)") }
                            putJsonObject("y2") { put("type", "number"); put("description", "End Y (absolute pixels, swipe)") }
                            putJsonObject("text") { put("type", "string"); put("description", "Text to type (type only)") }
                            putJsonObject("package") { put("type", "string"); put("description", "App package name (openapp)") }
                            putJsonObject("query") { put("type", "string"); put("description", "Find a node by text/description and click it (tap only, optional)") }
                            putJsonObject("durationMs") { put("type", "number"); put("description", "Gesture duration in milliseconds (long press for tap / swipe duration)") }
                            putJsonObject("maxItems") { put("type", "number"); put("description", "Maximum entries returned by readscreen (default 120)") }
                            putJsonObject("nodeId") {
                                put("type", "string")
                                put("description", "Node identity \"<snapshotId>:<seq>\" (from phone_tree). A stale-generation id returns E_STALE_SNAPSHOT and no action is dispatched")
                            }
                            putJsonObject("target") {
                                put("type", "object")
                                put("description", "Selector object: text/textContains/desc/viewId/viewIdSuffix/className/package/index/clickable/editable/scrollable (fields ANDed). Multiple hits return E_NODE_AMBIGUOUS; add index or use a more precise condition")
                            }
                            putJsonObject("index") {
                                put("type", "number")
                                put("description", "Which match to take when the selector hits several (0-based)")
                            }
                            putJsonObject("mode") { put("type", "string"); put("description", "type mode: set replaces / append appends / clear empties (default set)") }
                            putJsonObject("perChar") { put("type", "boolean"); put("description", "type character by character (default false)") }
                            putJsonObject("intervalMs") { put("type", "number"); put("description", "Per-character interval in milliseconds (perChar, 20-1000, default 80)") }
                            putJsonObject("key") { put("type", "string"); put("description", "back/home/recents/enter/delete/tab (key action)") }
                            putJsonObject("times") { put("type", "number"); put("description", "Repeat count (key/scroll, 1-20, default 1)") }
                            putJsonObject("direction") { put("type", "string"); put("description", "scroll direction: forward scrolls down / backward scrolls up") }
                            putJsonObject("verify") { put("type", "boolean"); put("description", "scroll: verify with a UI fingerprint that the content actually moved (default true)") }
                            putJsonObject("until") { put("type", "object"); put("description", "scroll target selector: stop once it matches (stoppedReason=until)") }
                            putJsonObject("cond") {
                                put("type", "object")
                                put("description", "wait_for condition: type=node_present/node_absent (with selector), text_present (with textContains), package_is (with package), generation_changed")
                            }
                            putJsonObject("timeoutMs") { put("type", "number"); put("description", "wait_for timeout in milliseconds (100-15000, default 3000); on timeout returns E_WAIT_TIMEOUT") }
                            putJsonObject("pollMs") { put("type", "number"); put("description", "wait_for poll interval in milliseconds (100-2000, default 250)") }
                            putJsonObject("scale") { put("type", "number"); put("description", "pinch scale: <1 zooms out, >1 zooms in (or pass fromRadius/toRadius directly)") }
                            putJsonObject("fromRadius") { put("type", "number"); put("description", "pinch start radius in pixels (>=20)") }
                            putJsonObject("toRadius") { put("type", "number"); put("description", "pinch end radius in pixels (>=20)") }
                            putJsonObject("cx") { put("type", "number"); put("description", "pinch center X (absolute pixels, defaults to screen center)") }
                            putJsonObject("cy") { put("type", "number"); put("description", "pinch center Y (absolute pixels, defaults to screen center)") }
                            putJsonObject("startTarget") { put("type", "object"); put("description", "swipe start selector or nodeId string") }
                            putJsonObject("endTarget") { put("type", "object"); put("description", "swipe end selector or nodeId string") }
                            putJsonObject("fromX") { put("type", "number"); put("description", "drag start X (or x)") }
                            putJsonObject("fromY") { put("type", "number"); put("description", "drag start Y (or y)") }
                            putJsonObject("toX") { put("type", "number"); put("description", "drag end X (or give a dx delta)") }
                            putJsonObject("toY") { put("type", "number"); put("description", "drag end Y (or give a dy delta)") }
                            putJsonObject("dx") { put("type", "number"); put("description", "drag horizontal delta (used when toX is omitted)") }
                            putJsonObject("dy") { put("type", "number"); put("description", "drag vertical delta (used when toY is omitted)") }
                            putJsonObject("holdMs") { put("type", "number"); put("description", "drag press-and-hold milliseconds (0-2000, default 500)") }
                            putJsonObject("steps") {
                                put("type", "array")
                                put("description", "sequence step array (<=20 steps): {action, args?, waitAfterMs?}. Actions: tap/swipe/scroll/type/key/pinch/drag/wait_for/openapp/back/home/recents; action parameters may also be written directly on the step (no need to wrap them in args)")
                            }
                            putJsonObject("stopOnError") { put("type", "boolean"); put("description", "sequence: stop at the first failed step (default true, no rollback)") }
                            putJsonObject("dryRun") { put("type", "boolean"); put("description", "sequence: validate parameters only, do not execute (default false)") }
                            putJsonObject("limit") { put("type", "number"); put("description", "apps page size (1-500, default 120)") }
                            putJsonObject("offset") { put("type", "number"); put("description", "apps page start offset (default 0)") }
                            putJsonObject("includeSystem") { put("type", "boolean"); put("description", "apps: include system apps (default false)") }
                            putJsonObject("maxDepth") { put("type", "number"); put("description", "action side tree depth limit (default 6, same meaning as phone_tree)") }
                            putJsonObject("maxNodes") { put("type", "number"); put("description", "action side node limit (default 400, same meaning as phone_tree)") }
                            putJsonObject("includeInvisible") { put("type", "boolean"); put("description", "action side: include invisible nodes (default false)") }
                            putJsonObject("onlyClickable") { put("type", "boolean"); put("description", "action side: keep only clickable nodes (default false)") }
                            putJsonObject("filter") { put("type", "object"); put("description", "action side tree filter selector (same meaning as phone_tree filter, keeps seq consistent)") }
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
                            putJsonObject("maxDepth") { put("type", "integer"); put("description", "Maximum depth, default 6 (clamped to 1..64)") }
                            putJsonObject("maxNodes") { put("type", "integer"); put("description", "Maximum nodes returned, default 400 (clamped to 1..2000)") }
                            putJsonObject("includeInvisible") { put("type", "boolean"); put("description", "Include invisible nodes, default false") }
                            putJsonObject("excludeOwnPackage") { put("type", "boolean"); put("description", "Exclude the phone-control overlay/windows themselves, default true") }
                            putJsonObject("package") { put("type", "string"); put("description", "Only windows of that package (optional)") }
                            putJsonObject("onlyClickable") { put("type", "boolean"); put("description", "Return only clickable nodes, default false") }
                            putJsonObject("filter") {
                                put("type", "object")
                                put("description", "Node filter selector; fields are ANDed and omitted fields do not participate")
                                putJsonObject("properties") {
                                    putJsonObject("text") { put("type", "string"); put("description", "text exact match") }
                                    putJsonObject("textContains") { put("type", "string"); put("description", "text contains (case-insensitive)") }
                                    putJsonObject("desc") { put("type", "string"); put("description", "contentDescription exact match") }
                                    putJsonObject("viewId") { put("type", "string"); put("description", "Full resource-id, e.g. com.android.settings:id/search") }
                                    putJsonObject("viewIdSuffix") { put("type", "string"); put("description", "id segment only (the part after the colon)") }
                                    putJsonObject("className") { put("type", "string"); put("description", "Widget class name exact match") }
                                    putJsonObject("package") { put("type", "string"); put("description", "Package the node belongs to") }
                                    putJsonObject("index") { put("type", "integer"); put("description", "Which match to take (0-based)") }
                                    putJsonObject("clickable") { put("type", "boolean"); put("description", "Must be clickable") }
                                    putJsonObject("editable") { put("type", "boolean"); put("description", "Must be editable") }
                                    putJsonObject("scrollable") { put("type", "boolean"); put("description", "Must be scrollable") }
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
                put("description", "Read a native file")
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
                put("description", "Write a native file")
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
                put("description", "List a native directory")
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
                put("description", "Operate on native files")
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
                put("description", "List shell channels and their availability")
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
                put("description", "Start a background command")
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
                put("description", "Query a background process and its log")
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
                put("description", "Terminate a background process")
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
                put("description", "Read the full instructions (SKILL.md) of an installed skill. When a task matches a skill description, call this tool first and then follow the skill.")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string"); put("description", "Skill name") }
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
                                "The full task to delegate: goal, what to look at, expected output shape. The subagent cannot see the current conversation; do not write \"the file above\".",
                            )
                        }
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "Short label (2-6 chars) used only for progress display, e.g. \"check logs\"")
                        }
                        putJsonObject("model") {
                            put("type", "string")
                            put("description", "Optional: run this subtask on another model; leave empty to inherit the current model")
                        }
                        putJsonObject("depth") {
                            put("type", "integer")
                            put("description", "The caller's level (main agent 0, first-level subagent 1); omit to use the runtime depth," +
                                "a dispatch beyond the nesting limit is rejected with \"subagents nest at most N levels\"")
                        }
                    }
                    putJsonArray("required") { add("prompt") }
                }
            }
}

    private fun phoneHelp(): String = buildString {
        append("phone tool: control the device directly (no root/ADB needed; first enable \"DSH Mobile phone control\" under Settings -> Accessibility).")
        append("action: tap (click; fx/fy fractional coordinates are most reliable, or x/y pixels; add query to click by text; add durationMs for a long press)")
        append(", swipe (start fx/fy or x/y, end fx2/fy2 or x2/y2, durationMs controls speed)")
        append(", type (write into the focused input, parameter text), back/home/recents (system keys)")
        append(", readscreen (read current screen text as a compact list, maxItems caps entries)")
        append(", openapp (package name, or query to fuzzy-match the app name), screenshot (Android 11+ writes a local file).")
        append("Prefer fx/fy coordinates (0-1 relative to the screen): the model sees a scaled screenshot, so absolute pixels tend to miss.")
        append("Each readscreen line is text|desc|cx,cy|clickable|class.")
        append("For structured screen reads (viewId/bounds/node identity/snapshot generation) use the phone_tree tool.")
        append("Address nodes with target (selector object) or nodeId (\"<snapshotId>:<seq>\", from phone_tree):")
        append("a stale-generation nodeId returns E_STALE_SNAPSHOT and an ambiguous selector returns E_NODE_AMBIGUOUS; neither dispatches any action.")
        append("If the matched node is not clickable it walks up to the nearest clickable ancestor (list rows only work through this).")
        append("New actions: scroll (scroll a scrollable=true container, direction/times/until/verify; the advanced path uses a UI fingerprint check)")
        append(", wait_for (cond.type=node_present/node_absent/text_present/package_is/generation_changed, timeoutMs default 3000, returns E_WAIT_TIMEOUT on timeout)")
        append(", pinch (center or cx/cy plus scale or fromRadius/toRadius), drag (fromX/fromY -> toX/toY or dx/dy, holdMs)")
        append(", key (enter/delete/tab go to the focused input; back/home/recents are system keys), currentapp (current foreground app)")
        append(", apps (installed launchable apps, query/limit/offset paging), sequence (send steps at once; stops at the first failed step and reports its number)")
        append(", type also supports target + mode=set/append/clear + perChar typing (intervalMs).")
        append("Six system capability actions (independent of accessibility, need runtime permissions; a missing grant returns E_PERMISSION_DENIED + needsPermission):")
        append("contacts (query looks up contacts), sms_list (reads inbox), sms_send (number+text), dial (number to call directly),")
        append("location (last known position), audio_record (durationMs recording). These three grants are configured under Settings -> Phone capability grants.")
        append("New actions always return ok=true at the outer level; the real outcome and error code are in the inner {ok,code,message,retryable,effectLanded}; phone actions are never retried.")
    }

    /**
     * phone_tree 的说明。
     *
     * 与 [phoneHelp] 分开写是因为它讲的是「读」而不是「动作」；T4.3 要把这两段与
     * PhoneBridge.describeForModel() 合并成单一真源，届时一起搬。
     */
    private fun phoneTreeHelp(): String = buildString {
        append("phone_tree: read the current screen as a structured node tree (first enable \"DSH Mobile phone control\" under Settings -> Accessibility).")
        append("Returns snapshotId and a flat nodes[], each node carrying nodeId (\"<snapshotId>:<seq>\"), parentId, depth,")
        append("className/package/text/desc/viewId/bounds{left,top,right,bottom}/clickable/editable/scrollable/")
        append("checkable/checked/selected/enabled/focused/visible, plus screen{width,height,density},")
        append("foreground{package,activity,sinceMs}, nodeCount, truncated + truncatedReason, changedDuringRead, elapsedMs.")
        append("Parameters: maxDepth default 6, maxNodes default 400, includeInvisible default false, onlyClickable default false,")
        append("package restricts to that package; filter is a selector object (text/textContains/desc/viewId/viewIdSuffix/className/package/")
        append("index/clickable/editable/scrollable, ANDed together); a filter with no match returns E_NODE_NOT_FOUND.")
        append("Node identity is generation-scoped: after the screen changes an old nodeId is void, read again for a new snapshotId; changedDuringRead=true means the screen changed during the walk.")
        append("Read-only, does not change the screen; use the phone tool to click or type.")
    }

    private fun subagentHelp(): String = buildString {
        append("subagent tool: hand independent, parallelizable subtasks to a subagent.")
        append("The subagent has its own context and tools (shell / files / phone) and returns only its conclusion,")
        append("so it neither occupies nor pollutes the main conversation history - good for \"check three things at once\" or \"read a big pile of code, then give me the conclusion\".")
        append("It cannot see the current conversation, so the prompt must carry all the background; do not write \"the file above\".")
        append("Nesting is depth-limited (default 2 levels, the main agent counts as level 0): level 2 may still dispatch, level 3 is rejected with \"subagents nest at most 2 levels\".")
        append("For unrelated subtasks dispatch several subagent calls in the same turn: they run in order and each returns its own conclusion.")
        append("To hand work to the execution pool, list it explicitly in your result - \"- task: <title> | <detail>\", one per line,")
        append("or a JSON array [{\"title\":\"..\",\"detail\":\"..\"}]; the parent session adds it to the execution pool and records the parent task id.")
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
