package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt


internal fun PhoneBridge.screenSize(): Pair<Int, Int> {
    val dm = context.resources.displayMetrics
    return dm.widthPixels to dm.heightPixels
}

internal fun PhoneBridge.point(obj: JSONObject, keyX: String, keyY: String, keyFx: String, keyFy: String): Pair<Int, Int>? {
    val (w, h) = screenSize()
    val fx = obj.optDouble(keyFx, -1.0)
    val fy = obj.optDouble(keyFy, -1.0)
    if (fx in 0.0..1.0 && fy in 0.0..1.0) return (fx * w).roundToInt() to (fy * h).roundToInt()
    if (obj.has(keyX) && obj.has(keyY)) return obj.getInt(keyX) to obj.getInt(keyY)
    return null
}

internal fun PhoneBridge.doTap(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    // T4.2：给了 target/nodeId/filter 就走选择器/节点路径；旧参数形态的方法体一个字都不动。
    val spec = PhoneTarget.parse(obj)
    if (spec != PhoneTargetSpec.None) return doTapTarget(svc, obj, spec)
    val query = obj.optString("query", "").trim()
    if (query.isNotEmpty()) return tapByQuery(svc, query)
    val p = point(obj, "x", "y", "fx", "fy")
        ?: return PhoneResult(false, "", "tap requires query or coordinates (fx/fy or x/y)")
    val duration = obj.optLong("durationMs", 60L).coerceIn(20L, 5000L)
    val ok = gesture(svc, Path().apply { moveTo(p.first.toFloat(), p.second.toFloat()) }, duration)
    return if (ok) PhoneResult(true, "Tapped (" + p.first + "," + p.second + "), duration " + duration + "ms")
    else PhoneResult(false, "", "dispatchGesture rejected the tap gesture")
}

internal fun PhoneBridge.doSwipe(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    if (obj.has("startTarget") || obj.has("endTarget")) return doSwipeTarget(svc, obj)
    val a = point(obj, "x", "y", "fx", "fy")
        ?: return PhoneResult(false, "", "swipe requires start coordinates (fx/fy or x/y)")
    val b = point(obj, "x2", "y2", "fx2", "fy2")
        ?: return PhoneResult(false, "", "swipe requires end coordinates (fx2/fy2 or x2/y2)")
    val duration = obj.optLong("durationMs", 300L).coerceIn(20L, 5000L)
    val path = Path().apply {
        moveTo(a.first.toFloat(), a.second.toFloat())
        lineTo(b.first.toFloat(), b.second.toFloat())
    }
    val ok = gesture(svc, path, duration)
    return if (ok) PhoneResult(true, "Swiped from (" + a.first + "," + a.second + ") to (" + b.first + "," + b.second + "), duration " + duration + "ms")
    else PhoneResult(false, "", "dispatchGesture rejected the swipe gesture")
}

internal suspend fun PhoneBridge.doType(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    // 只有真要用 v2 能力（定位/模式/逐字）才换路径；plain text 的旧串一字不动。
    val spec = PhoneTarget.parse(obj)
    if (spec != PhoneTargetSpec.None || obj.has("perChar") || obj.has("mode")) return doTypeV2(svc, obj, spec)
    val text = obj.optString("text", "")
    if (text.isEmpty()) return PhoneResult(false, "", "type requires the text parameter")
    val outcome = a11y(6000) {
        val node = findEditable(svc)
        when {
            node == null -> "NO_EDITABLE"
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
            ) -> "OK"
            else -> "FAIL"
        }
    }
    return when (outcome) {
        null -> PhoneResult(false, "", "Input timed out or the service is unavailable")
        "NO_EDITABLE" -> PhoneResult(false, "", "No editable field found (this screen may have none, or the field is a WebView/contenteditable)")
        "FAIL" -> PhoneResult(false, "", "ACTION_SET_TEXT failed (the field may be a WebView/contenteditable, which does not accept accessibility writes)")
        else -> PhoneResult(true, "Typed " + text.length + " characters")
    }
}

internal fun PhoneBridge.doGlobal(svc: AccessibilityService, action: Int, label: String): PhoneResult {
    return if (svc.performGlobalAction(action)) PhoneResult(true, "Executed " + label)
    else PhoneResult(false, "", label + " failed")
}

// ─────────────────────────────────────────────────────────────────────
// T4.2 动作侧：选择器 / nodeId 定位 + 新原语（scroll/pinch/drag/wait_for/
// currentapp/apps/sequence/key，以及 tap/type/swipe 的新参数形态）
//
// 统一约定：新动作回 PhoneResult(ok = true)，业务成败与错误码放进内层错误包。
// 这样错误码原样到模型，执行器也不会把「业务失败」当异常重试（副作用会被重放）。
// 旧 9 个动作与 tree 的方法体、返回串一个字都没动。
// ─────────────────────────────────────────────────────────────────────

/** 活树定位：Hit 带的是 walkTree 的下标（infos 与 nodes 同序），Fail 带组装好的错误包。 */
internal sealed interface LiveResolve {
    class Hit(val walked: Walked, val index: Int) : LiveResolve

    class Fail(val payload: JSONObject) : LiveResolve
}

/** 动作侧读树：与 phone_tree 同一个 walkTree、同一套过滤与上限，所以 seq 与它一致。 */
internal fun PhoneBridge.readActionTree(svc: AccessibilityService, p: TreeParams): Walked? =
    a11y(8_000L) { walkTree(svc, generationOf(svc), p, wantInfo = true) }

internal fun PhoneBridge.resolveLive(walked: Walked, spec: PhoneTargetSpec, generation: Long): LiveResolve =
    when (val r = PhoneTarget.resolve(spec, walked.nodes, generation)) {
        is PhoneResolveResult.Hit -> LiveResolve.Hit(walked, r.index)
        is PhoneResolveResult.Ambiguous -> LiveResolve.Fail(ambiguousPayload(r))
        PhoneResolveResult.NotFound -> LiveResolve.Fail(
            errorEnvelope(PhoneErrorCode.NodeNotFound, "No matching node; inspect the current screen with phone_tree first, then pass target"),
        )
        PhoneResolveResult.StaleSnapshot -> {
            val from = (spec as? PhoneTargetSpec.ByNodeId)?.snapshotId ?: -1L
            LiveResolve.Fail(
                errorEnvelope(
                    PhoneErrorCode.StaleSnapshot,
                    "nodeId belongs to a stale snapshot " + from + "; current generation " + generation +
                        "; call phone_tree again for a fresh nodeId (no action was dispatched)",
                    effectLanded = false,
                ),
            )
        }
    }

internal fun PhoneBridge.ambiguousPayload(r: PhoneResolveResult.Ambiguous): JSONObject {
    val candidates = JSONArray()
    for (n in r.candidates) candidates.put(nodeJson(n))
    return errorEnvelope(
        PhoneErrorCode.NodeAmbiguous,
        "Matched " + r.count + " nodes; add index or use a more precise selector (no action was dispatched)",
        effectLanded = false,
    ).put("count", r.count).put("candidates", candidates)
}

internal fun PhoneBridge.errorEnvelope(code: PhoneErrorCode, message: String, effectLanded: Boolean? = null): JSONObject =
    if (effectLanded == null) phoneErrorJson(code, message) else phoneErrorJson(code, message, effectLanded)

internal fun PhoneBridge.failResult(code: PhoneErrorCode, message: String, effectLanded: Boolean? = null): PhoneResult =
    PhoneResult(true, errorEnvelope(code, message, effectLanded).toString())

internal fun PhoneBridge.okEnvelope(started: Long, body: JSONObject): PhoneResult =
    PhoneResult(true, body.put("ok", true).put("elapsedMs", System.currentTimeMillis() - started).toString())

internal fun PhoneBridge.boundsJson(b: PhoneBounds): JSONObject = JSONObject()
    .put("left", b.left).put("top", b.top).put("right", b.right).put("bottom", b.bottom)
    .put("cx", b.centerX).put("cy", b.centerY)

internal fun PhoneBridge.pointJson(p: Pair<Int, Int>): JSONObject = JSONObject().put("x", p.first).put("y", p.second)

internal fun PhoneBridge.pointOf(obj: JSONObject, keyX: String, keyY: String): Pair<Int, Int>? {
    if (!obj.has(keyX) || !obj.has(keyY)) return null
    val x = obj.optInt(keyX, -1)
    val y = obj.optInt(keyY, -1)
    if (x < 0 || y < 0) return null
    return x to y
}

internal fun PhoneBridge.tapPath(b: PhoneBounds): Path =
    Path().apply { moveTo(b.centerX.toFloat(), b.centerY.toFloat()) }

internal fun PhoneBridge.setTextOn(info: AccessibilityNodeInfo, text: String): Boolean =
    info.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
    )

/** 焦点输入框：优先真焦点，退而求其次找第一个可编辑节点（它们对 IME 动作才有效）。 */
internal fun PhoneBridge.focusedEditableOrNull(svc: AccessibilityService): AccessibilityNodeInfo? {
    val focus = a11y(2_000L) { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
    if (focus != null && focus.isEditable) return focus
    return findEditable(svc)
}

/** tap 的新路径：选择器或 nodeId。命中自身不可点就上溯最近可点祖先（列表行靠这个才点得动）。 */
internal fun PhoneBridge.doTapTarget(svc: AccessibilityService, obj: JSONObject, spec: PhoneTargetSpec): PhoneResult {
    val started = System.currentTimeMillis()
    if (spec == PhoneTargetSpec.Invalid) {
        return failResult(PhoneErrorCode.BadArgs, "target does not resolve: pass {nodeId} or a non-empty selector object")
    }
    val p = parseTreeParams(obj) ?: return failResult(PhoneErrorCode.BadArgs, "filter must be an object")
    val walked = readActionTree(svc, p) ?: return failResult(PhoneErrorCode.Timeout, "Node tree read timed out")
    val index = when (val r = resolveLive(walked, spec, generationOf(svc))) {
        is LiveResolve.Fail -> return PhoneResult(true, r.payload.toString())
        is LiveResolve.Hit -> r.index
    }
    val nodes = walked.nodes
    val hit = nodes[index]
    val clickIndex = PhoneTarget.clickableAncestorIndex(nodes, index)
    val useIndex = if (clickIndex >= 0) clickIndex else index
    val info = walked.infos.getOrNull(useIndex)
    // 点的是「实际可点的那个节点」：上溯祖先时手势也得落在祖先中心，否则点回原来的死文本。
    val tapBounds = nodes[useIndex].bounds
    val allowGesture = obj.optBoolean("gestures", true)
    var route = "none"
    if (info != null && info.isClickable && info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        route = "node"
    } else if (allowGesture) {
        route = if (gesture(svc, tapPath(tapBounds), 40L)) "gesture" else "none"
    }
    val body = JSONObject()
        .put("ok", route != "none")
        .put("dispatched", route != "none")
        .put("route", route)
        .put("nodeId", hit.nodeId)
        .put("tappedNodeId", nodes[useIndex].nodeId)
        .put("viaAncestor", clickIndex >= 0)
        .put("bounds", boundsJson(tapBounds))
        .put("elapsedMs", System.currentTimeMillis() - started)
    if (route == "none") {
        body.put("code", PhoneErrorCode.ActionRejected.code)
            .put("retryable", false)
            .put("message", "The node is not clickable and the gesture did not land: add clickable=true to target to walk up, or pass coordinates")
    }
    return PhoneResult(true, body.toString())
}

/** swipe 的新路径：startTarget/endTarget 各自解成点（nodeId 串或选择器对象）。 */
internal fun PhoneBridge.doSwipeTarget(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    val started = System.currentTimeMillis()
    val p = parseTreeParams(obj) ?: return failResult(PhoneErrorCode.BadArgs, "filter must be an object")
    val walked = readActionTree(svc, p) ?: return failResult(PhoneErrorCode.Timeout, "Node tree read timed out")
    val generation = generationOf(svc)
    val from = resolvePointOn(walked, obj.opt("startTarget"), generation)
        ?: return failResult(PhoneErrorCode.NodeNotFound, "startTarget did not resolve to a node")
    val to = resolvePointOn(walked, obj.opt("endTarget"), generation)
        ?: return failResult(PhoneErrorCode.NodeNotFound, "endTarget did not resolve to a node")
    val durationMs = obj.optLong("durationMs", 300L).coerceIn(100L, 5_000L)
    val path = Path().apply {
        moveTo(from.first.toFloat(), from.second.toFloat())
        lineTo(to.first.toFloat(), to.second.toFloat())
    }
    val dispatched = gesture(svc, path, durationMs)
    return PhoneResult(
        true,
        JSONObject()
            .put("ok", dispatched)
            .put("dispatched", dispatched)
            .put("route", "gesture")
            .put("from", pointJson(from))
            .put("to", pointJson(to))
            .put("durationMs", durationMs)
            .put("elapsedMs", System.currentTimeMillis() - started)
            .toString(),
    )
}

/** 单个角色的定位点：接受 nodeId 字符串、选择器对象或 {nodeId} 对象。 */
internal fun PhoneBridge.resolvePointOn(walked: Walked, raw: Any?, generation: Long): Pair<Int, Int>? {
    val spec = when (raw) {
        is String -> PhoneTarget.parse(JSONObject().put("nodeId", raw))
        is JSONObject -> PhoneTarget.parse(JSONObject().put("target", raw))
        else -> return null
    }
    return when (val r = PhoneTarget.resolve(spec, walked.nodes, generation)) {
        is PhoneResolveResult.Hit -> r.node.bounds.centerX to r.node.bounds.centerY
        else -> null
    }
}

/**
 * type 的新路径：target 定位 + mode=set/append/clear + perChar 逐字。
 *
 * 逐字用的是「逐字符前缀 + intervalMs 间隔」：每次 ACTION_SET_TEXT 只多一个字，
 * 输入法的过滤/联想会按字符触发，和一次性灌入语义不同。真正的按键注入需要 IME 侧支持，
 * 没有就如实回 E_ACTION_REJECTED，而不是假装输入成功。
 */
internal suspend fun PhoneBridge.doTypeV2(svc: AccessibilityService, obj: JSONObject, spec: PhoneTargetSpec): PhoneResult {
    val started = System.currentTimeMillis()
    if (spec == PhoneTargetSpec.Invalid) return failResult(PhoneErrorCode.BadArgs, "target does not resolve")
    val mode = obj.optString("mode", "set").lowercase()
    if (mode != "set" && mode != "append" && mode != "clear") {
        return failResult(PhoneErrorCode.BadArgs, "mode must be set/append/clear")
    }
    val raw = obj.optString("text", "")
    if (mode != "clear" && raw.isEmpty()) return failResult(PhoneErrorCode.BadArgs, "type requires text (except mode=clear)")
    val perChar = obj.optBoolean("perChar", false)
    val intervalMs = obj.optLong("intervalMs", 80L).coerceIn(20L, 1_000L)
    val p = parseTreeParams(obj) ?: return failResult(PhoneErrorCode.BadArgs, "filter must be an object")
    val walked = readActionTree(svc, p) ?: return failResult(PhoneErrorCode.Timeout, "Node tree read timed out")
    val resolved = if (spec == PhoneTargetSpec.None) {
        val i = walked.nodes.indexOfFirst { it.editable && it.enabled }
        if (i < 0) {
            return failResult(PhoneErrorCode.NoFocusedEditable, "No editable node (includeInvisible=true may reveal it)")
        }
        LiveResolve.Hit(walked, i)
    } else {
        resolveLive(walked, spec, generationOf(svc))
    }
    val index = when (resolved) {
        is LiveResolve.Fail -> return PhoneResult(true, resolved.payload.toString())
        is LiveResolve.Hit -> resolved.index
    }
    val node = walked.nodes[index]
    val info = walked.infos.getOrNull(index) ?: return failResult(PhoneErrorCode.NodeNotFound, "The node handle is no longer valid")
    val target = when (mode) {
        "clear" -> ""
        "append" -> node.text + raw
        else -> raw
    }
    a11y(1_000L) { info.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
    var backend = "set_text"
    var sent = 0
    if (perChar && target.isNotEmpty()) {
        backend = "set_text_per_char"
        var prefix = ""
        for (ch in target) {
            prefix += ch
            if (setTextOn(info, prefix)) sent++ else break
            if (sent < target.length) cancellableDelay(intervalMs)
        }
    } else if (setTextOn(info, target)) {
        sent = target.length
    }
    if (sent < target.length) {
        val payload = errorEnvelope(
            PhoneErrorCode.ActionRejected,
            "The field did not accept ACTION_SET_TEXT (wrote " + sent + "/" + target.length + " characters); " +
                "use key to type character by character, or tap the field first and retry",
            effectLanded = sent > 0,
        )
        return PhoneResult(true, payload.put("backend", backend).put("requested", target.length).put("written", sent).toString())
    }
    val refreshed = info.refresh()
    val readBack = if (refreshed) info.text?.toString() ?: "" else ""
    return okEnvelope(
        started,
        JSONObject()
            .put("requested", target.length)
            .put("readBack", readBack)
            .put("readBackUnavailable", !refreshed)
            .put("mode", mode)
            .put("perChar", perChar)
            .put("backend", backend)
            .put("nodeId", node.nodeId),
    )
}

/** key：back/home/recents 走系统键；enter/delete/tab 只打在聚焦输入框上。 */
internal fun PhoneBridge.doKey(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    val started = System.currentTimeMillis()
    val key = obj.optString("key", "").trim().lowercase().ifEmpty { "back" }
    val times = obj.optInt("times", 1).coerceIn(1, 20)
    if (key == "back" || key == "home" || key == "recents") {
        val action = when (key) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> AccessibilityService.GLOBAL_ACTION_BACK
        }
        var dispatched = 0
        repeat(times) { if (svc.performGlobalAction(action)) dispatched++ }
        return okEnvelope(
            started,
            JSONObject()
                .put("ok", dispatched > 0)
                .put("backend", "global_action")
                .put("key", key)
                .put("requested", times)
                .put("dispatched", dispatched)
                .put("effectVerified", false),
        )
    }
    if (key != "enter" && key != "delete" && key != "tab") {
        return failResult(
            PhoneErrorCode.BadArgs,
            "key only supports back/home/recents/enter/delete/tab, got " + key,
        )
    }
    val outcome = a11y(3_000L) {
        val info = focusedEditableOrNull(svc)
        if (info == null) {
            return@a11y Pair(null, "No focused input field; tap/type into the field before sending key=" + key)
        }
        var dispatched = 0
        repeat(times) {
            val ok = when (key) {
                "enter" ->
                    // ACTION_IME_ENTER 只有 AccessibilityAction 形态（API 30+），低版本如实失败。
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        info.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                "delete" -> {
                    val cur = info.text?.toString().orEmpty()
                    cur.isNotEmpty() && setTextOn(info, cur.dropLast(1))
                }
                else -> {
                    val next = info.focusSearch(AccessibilityNodeInfo.FOCUS_INPUT)
                    next != null && next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
            }
            if (ok) dispatched++
        }
        Pair(dispatched, "")
    } ?: return failResult(PhoneErrorCode.Timeout, "The accessibility thread did not return within 3s")
    val dispatched = outcome.first
        ?: return PhoneResult(
            true,
            errorEnvelope(PhoneErrorCode.NoFocusedEditable, outcome.second, effectLanded = false)
                .put("key", key).toString(),
        )
    if (dispatched == 0) {
        val code = if (key == "enter") PhoneErrorCode.NodeNoImeAction else PhoneErrorCode.ActionRejected
        return failResult(code, "The node does not accept key=" + key + " (the control may not be an input field)")
    }
    return okEnvelope(
        started,
        JSONObject()
            .put("ok", true)
            .put("backend", "node_action")
            .put("key", key)
            .put("requested", times)
            .put("dispatched", dispatched)
            .put("effectVerified", false),
    )
}
