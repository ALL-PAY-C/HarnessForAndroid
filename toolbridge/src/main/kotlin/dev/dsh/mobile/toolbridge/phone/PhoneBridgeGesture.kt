package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


/** 滚动目标点：容器可滚就在容器里滑，否则在命中节点里滑（两种都真派发手势）。 */
internal fun PhoneBridge.gestureScroll(svc: AccessibilityService, b: PhoneBounds, forward: Boolean): Boolean {
    val x = b.centerX.toFloat()
    val span = (b.height * 0.4f).coerceAtLeast(120f)
    val mid = b.centerY.toFloat()
    val path = Path()
    if (forward) {
        path.moveTo(x, mid + span)
        path.lineTo(x, mid - span)
    } else {
        path.moveTo(x, mid - span)
        path.lineTo(x, mid + span)
    }
    return gesture(svc, path, 300L)
}

/**
 * scroll：优先 ACTION_SCROLL_FORWARD/BACKWARD，失败降级成手势滑。
 *
 * advanced 不看动作返回值，用滚动前后的树指纹判——dispatched=true 但没滚必须如实回。
 */
internal suspend fun PhoneBridge.doScroll(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    val started = System.currentTimeMillis()
    val direction = obj.optString("direction", "forward").lowercase()
    val forward = direction != "backward"
    val times = obj.optInt("times", 1).coerceIn(1, 20)
    val verify = obj.optBoolean("verify", true)
    val until = obj.opt("until")
    val untilSelector = if (until is JSONObject) PhoneSelector.parse(until) else null
    val spec = PhoneTarget.parse(obj)
    if (spec == PhoneTargetSpec.Invalid) return failResult(PhoneErrorCode.BadArgs, "target does not resolve")
    val p = parseTreeParams(obj) ?: return failResult(PhoneErrorCode.BadArgs, "filter must be an object")
    if (spec is PhoneTargetSpec.ByNodeId && spec.snapshotId != generationOf(svc)) {
        return failResult(
            PhoneErrorCode.StaleSnapshot,
            "nodeId belongs to a stale snapshot " + spec.snapshotId + "; current generation " + generationOf(svc) + " (no gesture was dispatched)",
            effectLanded = false,
        )
    }
    val generation0 = generationOf(svc)
    // 只读一次树：容器下标、活句柄、初始指纹都取自同一张快照，序号才对得上。
    val walked = readActionTree(svc, p) ?: return failResult(PhoneErrorCode.Timeout, "Node tree read timed out")
    val containerIndex: Int
    val container: PhoneBounds
    if (spec == PhoneTargetSpec.None) {
        containerIndex = walked.nodes.indexOfFirst { it.scrollable && it.enabled }
        if (containerIndex < 0) {
            return failResult(PhoneErrorCode.NodeNotActionable, "No scrollable=true container on screen; point target at a scrollable node")
        }
        container = walked.nodes[containerIndex].bounds
    } else {
        val index = when (val r = resolveLive(walked, spec, generation0)) {
            is LiveResolve.Fail -> return PhoneResult(true, r.payload.toString())
            is LiveResolve.Hit -> r.index
        }
        val up = PhoneTarget.scrollableAncestorIndex(walked.nodes, index)
        containerIndex = up ?: -1
        container = walked.nodes[if (up != null) up else index].bounds
    }
    var advanced = false
    var scrolls = 0
    var stoppedEarly = false
    var stoppedReason = "times"
    val info = walked.infos.getOrNull(containerIndex)
    // 判据用「树代次」而不是树指纹：代次由无障碍事件（CONTENT_CHANGED / WINDOWS_CHANGED）推进，
    // 事件驱动没有指纹那样的一致性延迟（实测同一次广播里连读 2.5s 指纹始终没变，
    // 而前后两次广播读到的内容确实移动了）。代次没动 = 这次滚动没落地。
    suspend fun awaitGeneration(since: Long, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (generationOf(svc) != since) return true
            cancellableDelay(60)
        }
        return generationOf(svc) != since
    }
    for (i in 1..times) {
        val genBefore = generationOf(svc)
        var dispatched = false
        if (info != null && info.isScrollable) {
            dispatched = info.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            )
        }
        if (dispatched) scrolls++
        // 无障碍滚动动作在这类 Compose 列表上会「返回 true 但一像素都不动」（实测 Settings SPA 列表），
        // 所以没落地就退回手势滑动再试一次。
        var moved = if (dispatched) awaitGeneration(genBefore, 600L) else false
        if (!moved) {
            val swiped = gestureScroll(svc, container, forward)
            if (swiped) scrolls++
            moved = swiped && awaitGeneration(generationOf(svc), 1500L)
        }
        if (moved) advanced = true
        if (untilSelector != null) {
            val now = a11y(8_000L) { walkTree(svc, generationOf(svc), p, wantInfo = false) }
            if (now != null && untilSelector.select(now.nodes).isNotEmpty()) {
                stoppedEarly = true
                stoppedReason = "until"
                break
            }
        }
    }
    val body = JSONObject()
        .put("ok", scrolls > 0)
        .put("dispatched", scrolls > 0)
        .put("advanced", if (verify) advanced else scrolls > 0)
        .put("scrolls", scrolls)
        .put("requested", times)
        .put("stoppedEarly", stoppedEarly)
        .put("stoppedReason", stoppedReason)
        .put("containerNodeId", if (containerIndex >= 0) "$generation0:$containerIndex" else "")
        .put("container", boundsJson(container))
        .put("elapsedMs", System.currentTimeMillis() - started)
    if (scrolls == 0) {
        body.put("code", PhoneErrorCode.ActionRejected.code).put("retryable", false)
            .put("message", "Neither a scroll action nor a gesture landed")
    }
    return PhoneResult(true, body.toString())
}

/** pinch：两指相向（scale<1）或相背（scale>1），radius 夹在屏幕内。 */
internal fun PhoneBridge.doPinch(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    val started = System.currentTimeMillis()
    val center = pointOf(obj, "x", "y") ?: pointOf(obj, "cx", "cy")
    val explicit = pointOf(obj, "fromRadius", "toRadius")
    val scale = obj.optDouble("scale", 0.0)
    val centerPoint = center ?: Pair(screenSize().first / 2, screenSize().second / 2)
    val fromRadius = explicit?.first ?: ((scale.takeIf { it > 0.0 }?.let { 1.0 / it } ?: 1.0) * 300.0).toInt()
    val toRadius = explicit?.second ?: (fromRadius * (scale.takeIf { it > 0.0 } ?: 1.0)).toInt()
    if (fromRadius < 20 || toRadius < 20) {
        return failResult(PhoneErrorCode.BadArgs, "Radius must be at least 20px (fromRadius/toRadius or scale)")
    }
    // 手势路径的坐标不能越界：GestureDescription 对负坐标直接抛
    // IllegalArgumentException("Path bounds must not be negative")，真机上表现为整条命令崩掉。
    // 水平双指绕中心对称，所以可用半径被左右两侧较窄的一边限制。
    val screen = screenSize()
    val room = minOf(centerPoint.first, screen.first - 1 - centerPoint.first).coerceAtLeast(0)
    val fitFrom = minOf(fromRadius, room)
    val fitTo = minOf(toRadius, room)
    if (fitFrom < 20 || fitTo < 20) {
        return failResult(
            PhoneErrorCode.BadArgs,
            "Center (" + centerPoint.first + "," + centerPoint.second + ") is only " + room +
                "px from a screen edge, which cannot fit the zoom radius; pick a more central center (cx/cy) or shrink the radius",
        )
    }
    val durationMs = obj.optLong("durationMs", 400L).coerceIn(100L, 3_000L)
    val p1 = Path().apply {
        moveTo((centerPoint.first - fitFrom).toFloat(), centerPoint.second.toFloat())
        lineTo((centerPoint.first - fitTo).toFloat(), centerPoint.second.toFloat())
    }
    val p2 = Path().apply {
        moveTo((centerPoint.first + fitFrom).toFloat(), centerPoint.second.toFloat())
        lineTo((centerPoint.first + fitTo).toFloat(), centerPoint.second.toFloat())
    }
    val strokes = runCatching {
        listOf(
            GestureDescription.StrokeDescription(p1, 0L, durationMs),
            GestureDescription.StrokeDescription(p2, 0L, durationMs),
        )
    }.getOrElse {
        return failResult(PhoneErrorCode.GestureRejected, "The system rejected the two-finger path: " + it.message)
    }
    val dispatched = gestureStrokes(svc, strokes)
    val body = JSONObject()
        .put("ok", dispatched)
        .put("dispatched", dispatched)
        .put("strokes", strokes.size)
        .put("center", pointJson(centerPoint))
        .put("fromRadius", fitFrom)
        .put("toRadius", fitTo)
        .put("durationMs", durationMs)
        .put("elapsedMs", System.currentTimeMillis() - started)
    if (!dispatched) body.put("code", PhoneErrorCode.GestureRejected.code).put("retryable", false)
    return PhoneResult(true, body.toString())
}

/** drag：单指按下 —— hold 段 —— 位移。续段用 continueStroke，父段必须先 addStroke。 */
internal fun PhoneBridge.doDrag(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    // 目标形态优先：给了 startTarget/endTarget 就按节点定位（与 swipe 的写法一致），
    // 只给坐标的老形态原样保留。
    if (obj.has("startTarget") || obj.has("endTarget")) {
        val p = parseTreeParams(obj) ?: return failResult(PhoneErrorCode.BadArgs, "filter must be an object")
        val walked = readActionTree(svc, p) ?: return failResult(PhoneErrorCode.Timeout, "Node tree read timed out")
        val generation = generationOf(svc)
        val fromNode = resolvePointOn(walked, obj.opt("startTarget"), generation)
            ?: return failResult(PhoneErrorCode.NodeNotFound, "startTarget did not resolve to a node")
        val toNode = resolvePointOn(walked, obj.opt("endTarget"), generation)
            ?: return failResult(PhoneErrorCode.NodeNotFound, "endTarget did not resolve to a node")
        return dragPoints(svc, obj, fromNode, toNode)
    }
    val from = pointOf(obj, "fromX", "fromY") ?: pointOf(obj, "x", "y")
        ?: return failResult(PhoneErrorCode.BadArgs, "drag requires fromX/fromY (or an x/y start)")
    val to = pointOf(obj, "toX", "toY") ?: run {
        val dx = obj.optInt("dx", Int.MIN_VALUE)
        val dy = obj.optInt("dy", Int.MIN_VALUE)
        if (dx == Int.MIN_VALUE && dy == Int.MIN_VALUE) null
        else Pair(from.first + (if (dx == Int.MIN_VALUE) 0 else dx), from.second + (if (dy == Int.MIN_VALUE) 0 else dy))
    } ?: return failResult(PhoneErrorCode.BadArgs, "drag requires toX/toY or dx/dy")
    return dragPoints(svc, obj, from, to)
}

/** 从两个已定位的点执行拖拽：单指按下 → hold 段 → 续段位移。 */
internal fun PhoneBridge.dragPoints(
    svc: AccessibilityService,
    obj: JSONObject,
    from: Pair<Int, Int>,
    to: Pair<Int, Int>,
): PhoneResult {
    val started = System.currentTimeMillis()
    val holdMs = obj.optLong("holdMs", 500L).coerceIn(0L, 2_000L)
    val durationMs = obj.optLong("durationMs", 600L).coerceIn(100L, 5_000L)
    val down = Path().apply { moveTo(from.first.toFloat(), from.second.toFloat()) }
    val move = Path().apply {
        moveTo(from.first.toFloat(), from.second.toFloat())
        lineTo(to.first.toFloat(), to.second.toFloat())
    }
    val dispatched: Boolean
    if (holdMs > 0) {
        val willContinue = GestureDescription.StrokeDescription(down, 0L, holdMs, true)
        val builder = GestureDescription.Builder().addStroke(willContinue)
        if (dispatchGesture(svc, builder.build())) {
            val moveStroke = willContinue.continueStroke(move, 0L, durationMs, false)
            dispatched = gestureStrokes(svc, listOf(moveStroke))
        } else {
            dispatched = false
        }
    } else {
        dispatched = gestureStrokes(svc, listOf(GestureDescription.StrokeDescription(move, 0L, durationMs)))
    }
    val body = JSONObject()
        .put("ok", dispatched)
        .put("dispatched", dispatched)
        .put("strokes", if (holdMs > 0) 2 else 1)
        .put("from", pointJson(from))
        .put("to", pointJson(to))
        .put("holdMs", holdMs)
        .put("durationMs", durationMs)
        .put("elapsedMs", System.currentTimeMillis() - started)
    if (!dispatched) body.put("code", PhoneErrorCode.GestureRejected.code).put("retryable", false)
    return PhoneResult(true, body.toString())
}

/** wait_for：轮询判条件，超时回独立 E_WAIT_TIMEOUT（不复用 E_TIMEOUT）。 */
internal suspend fun PhoneBridge.doWaitFor(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    val started = System.currentTimeMillis()
    val req = PhoneWait.parse(obj) ?: return failResult(PhoneErrorCode.BadArgs, "wait_for requires a cond object (type=…)")
    val phone = svcForeground()
    if (phone == null) return failResult(PhoneErrorCode.A11yOff, "Accessibility service is not enabled")
    if (req.cond is PhoneWaitCond.GenerationChanged) {
        return awaitGenerationChange(phone, req, started)
    }
    // 深度必须跟 phone_tree 同一套默认：写死 6 层会看不见列表行内文本，
    // 于是「界面上明明有这几个字」却永远等不到（实测 Settings 首页文本在第 7 层）。
    val p = parseTreeParams(obj)?.copy(selector = null, excludeOwnPackage = false)
        ?: TreeParams(
            maxNodes = 400,
            maxDepth = 12,
            includeInvisible = false,
            excludeOwnPackage = false,
            packageFilter = null,
            onlyClickable = false,
            selector = null,
        )
    val since = generationOf(svc)
    var polls = 0
    var satisfied = false
    var matched: PhoneNode? = null
    val deadline = started + req.timeoutMs
    while (true) {
        polls++
        val walked = a11y(8_000L) { walkTree(svc, generationOf(svc), p, wantInfo = false) }
        if (walked != null) {
            satisfied = PhoneWait.satisfied(
                req.cond,
                walked.nodes,
                phone.foregroundPackage(),
                generationOf(svc),
                since,
            )
            if (satisfied) {
                matched = PhoneWait.matchNode(req.cond, walked.nodes)
                break
            }
        }
        if (System.currentTimeMillis() >= deadline) break
        cancellableDelay(req.pollMs)
    }
    val payload = PhoneWait.describe(req.cond)
    if (!satisfied) {
        return PhoneResult(
            true,
            errorEnvelope(
                PhoneErrorCode.WaitTimeout,
                "Waited " + payload + " and timed out (" + req.timeoutMs + "ms, " + polls + " polls)",
                effectLanded = false,
            ).put("satisfied", false).put("requestedMs", req.timeoutMs)
                .put("waitedMs", System.currentTimeMillis() - started).put("polls", polls).toString(),
        )
    }
    return okEnvelope(
        started,
        JSONObject()
            .put("satisfied", true)
            .put("cond", payload)
            .put("requestedMs", req.timeoutMs)
            .put("waitedMs", System.currentTimeMillis() - started)
            .put("polls", polls)
            .put("snapshotId", generationOf(svc))
            .put("matchedNodeId", matched?.nodeId ?: ""),
    )
}

/** 代次变化用服务自带的 awaitChange 等，别再自己轮询一次。 */
internal suspend fun PhoneBridge.awaitGenerationChange(
    phone: PhoneAccessibilityService,
    req: PhoneWaitRequest,
    started: Long,
): PhoneResult {
    val since = phone.currentGeneration()
    val changed = runBlocking { phone.awaitChange(since, req.timeoutMs) }
    if (changed == null) {
        return PhoneResult(
            true,
            errorEnvelope(
                PhoneErrorCode.WaitTimeout,
                "Timed out waiting for the screen to change (" + req.timeoutMs + "ms, generation is still " + since + ")",
                effectLanded = false,
            ).put("satisfied", false).put("requestedMs", req.timeoutMs)
                .put("waitedMs", System.currentTimeMillis() - started).toString(),
        )
    }
    return okEnvelope(
        started,
        JSONObject()
            .put("satisfied", true)
            .put("cond", PhoneWait.describe(req.cond))
            .put("requestedMs", req.timeoutMs)
            .put("waitedMs", System.currentTimeMillis() - started)
            .put("snapshotId", changed),
    )
}

internal fun <T> PhoneBridge.a11y(timeoutMs: Long, block: () -> T): T? =
    try {
        a11yExecutor.submit<T> { block() }.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (t: Throwable) {
        null
    }

internal fun PhoneBridge.gesture(svc: AccessibilityService, path: Path, durationMs: Long): Boolean =
    gestureStrokes(svc, listOf(GestureDescription.StrokeDescription(path, 0L, durationMs)))

/** 多指/分段手势的唯一入口：pinch 两指、drag 是「hold 段 + 续段」一次下发。 */
internal fun PhoneBridge.gestureStrokes(svc: AccessibilityService, strokes: List<GestureDescription.StrokeDescription>): Boolean {
    if (strokes.isEmpty()) return false
    val builder = GestureDescription.Builder()
    for (stroke in strokes) builder.addStroke(stroke)
    return dispatchGesture(svc, builder.build())
}

internal fun PhoneBridge.dispatchGesture(svc: AccessibilityService, gesture: GestureDescription): Boolean {
    val done = CountDownLatch(1)
    val ok = AtomicReference(false)
    val accepted = svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            ok.set(true)
            done.countDown()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            ok.set(false)
            done.countDown()
        }
    }, null)
    if (!accepted) return false
    return try {
        done.await(3000, TimeUnit.MILLISECONDS) && ok.get()
    } catch (t: Throwable) {
        accepted
    }
}
