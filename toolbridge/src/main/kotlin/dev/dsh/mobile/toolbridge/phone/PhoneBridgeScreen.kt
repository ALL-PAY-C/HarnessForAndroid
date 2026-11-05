package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject


internal fun PhoneBridge.doReadScreen(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    val maxItems = obj.optInt("maxItems", 120).coerceIn(1, 500)
    val (w, h) = screenSize()
    val body = a11y(8000) {
        val out = StringBuilder()
        var count = 0
        outer@ for (root in rootsOf(svc)) {
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 4000) {
                val n = queue.removeFirst()
                visited++
                val text = n.text?.toString().orEmpty()
                val desc = n.contentDescription?.toString().orEmpty()
                if (text.isNotBlank() || desc.isNotBlank() || n.isClickable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    out.append(text.replace("\n", " ").take(80)).append('|')
                        .append(desc.replace("\n", " ").take(80)).append('|')
                        .append(r.centerX()).append(',').append(r.centerY()).append('|')
                        .append(if (n.isClickable) "1" else "0").append('|')
                        .append(n.className?.toString().orEmpty()).append("\n")
                    count++
                    if (count >= maxItems) break@outer
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
            }
        }
        "screen:" + w + "x" + h + "\n" + if (count == 0) "(no readable text nodes)" else out.toString().trimEnd()
    }
    return if (body != null) PhoneResult(true, body)
    else PhoneResult(false, "", "Screen read timed out or the service is unavailable")
}

// ---- T4.1 读侧：结构化节点树（旧 readscreen 路径不受影响，见 doReadScreen / rootsOf）----

internal data class TreeParams(
    val maxNodes: Int,
    val maxDepth: Int,
    val includeInvisible: Boolean,
    val excludeOwnPackage: Boolean,
    val packageFilter: String?,
    val onlyClickable: Boolean,
    val selector: PhoneSelector?,
)

/** 数值越界只夹取不报错（模型给 5000 就是想多要节点）；filter 不是对象才算参数非法。 */
internal fun PhoneBridge.parseTreeParams(obj: JSONObject): TreeParams? {
    val raw = obj.opt("filter")
    if (raw != null && raw != JSONObject.NULL && raw !is JSONObject) return null
    return TreeParams(
        maxNodes = obj.optInt("maxNodes", 400).coerceIn(1, 2000),
        // 默认 6 层在 RecyclerView 列表上读不到行内文本（行 → 子 View 常在 7~9 层），
        // 于是「界面明明有这几个字」却搜不到；12 层覆盖常见列表结构。
        maxDepth = obj.optInt("maxDepth", 12).coerceIn(1, 64),
        includeInvisible = obj.optBoolean("includeInvisible", false),
        excludeOwnPackage = obj.optBoolean("excludeOwnPackage", true),
        packageFilter = obj.optString("package", "").trim().takeIf { it.isNotEmpty() },
        onlyClickable = obj.optBoolean("onlyClickable", false),
        selector = if (raw is JSONObject) PhoneSelector.parse(raw) else null,
    )
}

/**
 * 读侧根节点：剔除自身浮层窗口与自身包名窗口。
 *
 * svc.windows 里包含本进程的 TYPE_ACCESSIBILITY_OVERLAY 浮层（updateOverlay 那个），
 * 不过滤的话「手机控制已就绪 · <pkg> · ✓tap」会作为界面节点喂回模型。
 * 旧 readscreen 走 rootsOf，行为保持不变。
 */
internal fun PhoneBridge.treeRoots(svc: AccessibilityService, p: TreeParams): List<AccessibilityNodeInfo> {
    val out = ArrayList<AccessibilityNodeInfo>()
    // 窗口顺序不可依赖：svc.windows 常把状态栏/导航栏排在前面，它们的子树会先吃掉 maxNodes，
    // 结果「当前界面」一个节点都读不到（实测 Settings 前置时 36 个节点全是 com.android.systemui）。
    // 所以按「焦点窗口 → 应用窗口 → 其它」排序，让真正的界面子树先被遍历。
    val ordered = svc.windows.sortedWith(
        compareBy(
            { if (it.isFocused) 0 else 1 },
            { if (it.type == AccessibilityWindowInfo.TYPE_APPLICATION) 0 else 1 },
        ),
    )
    for (w in ordered) {
        if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
        val root = w.root ?: continue
        if (!rootPasses(root, p)) continue
        out.add(root)
    }
    if (out.isEmpty()) {
        // 部分 ROM 只在窗口有焦点时才填 windows；退回活动窗口，但同一套剔除照做。
        val root = svc.rootInActiveWindow ?: return emptyList()
        if (rootPasses(root, p)) out.add(root)
    }
    return out
}

internal fun PhoneBridge.rootPasses(root: AccessibilityNodeInfo, p: TreeParams): Boolean {
    val pkg = root.packageName?.toString().orEmpty()
    if (p.excludeOwnPackage && pkg == context.packageName) return false
    if (p.packageFilter != null && pkg != p.packageFilter) return false
    return true
}

/**
 * 一次 BFS 的产物：不可变节点行 + 同序号的活节点句柄（wantInfo 时才收集）。
 *
 * 读侧（phone_tree）只要前者，动作侧要靠后者 performAction。两边必须走同一个遍历与同一套过滤，
 * 否则 nodeId 里的 seq 会对不上另一头数出来的序号。
 */
internal class Walked(
    val nodes: List<PhoneNode>,
    val infos: List<AccessibilityNodeInfo>,
    val truncated: Boolean,
    val reason: String?,
)

internal fun PhoneBridge.buildTree(svc: AccessibilityService, snapshotId: Long, p: TreeParams): PhoneSnapshot {
    val walked = walkTree(svc, snapshotId, p, wantInfo = false)
    return PhoneSnapshot(snapshotId, walked.nodes, walked.truncated, walked.reason)
}

internal fun PhoneBridge.walkTree(svc: AccessibilityService, snapshotId: Long, p: TreeParams, wantInfo: Boolean): Walked {
    val nodes = ArrayList<PhoneNode>()
    val infos = ArrayList<AccessibilityNodeInfo>()
    var truncated = false
    var reason: String? = null
    val queue = java.util.ArrayDeque<Triple<AccessibilityNodeInfo, Int, Int>>()
    for (root in treeRoots(svc, p)) queue.addLast(Triple(root, 0, -1))
    loop@ while (queue.isNotEmpty()) {
        if (nodes.size >= p.maxNodes) {
            truncated = true
            reason = "maxNodes"
            break@loop
        }
        val (n, depth, parentIndex) = queue.removeFirst()
        val seq = nodes.size
        val node = toNode(n, snapshotId, seq, parentIndex, depth)
        val keep = (p.includeInvisible || node.visible) &&
            (!p.onlyClickable || node.clickable) &&
            (p.selector == null || p.selector.matches(node))
        if (keep) {
            nodes.add(node)
            if (wantInfo) infos.add(n)
        }
        if (depth >= p.maxDepth) {
            if (n.childCount > 0) {
                truncated = true
                if (reason == null) reason = "maxDepth"
            }
            continue
        }
        // 父节点被过滤掉时子节点挂到最近的保留祖先，否则 parentId 会指向一张不存在的行。
        val childParent = if (keep) seq else parentIndex
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            queue.addLast(Triple(child, depth + 1, childParent))
        }
    }
    return Walked(nodes, infos, truncated, reason)
}

internal fun PhoneBridge.toNode(n: AccessibilityNodeInfo, snapshotId: Long, seq: Int, parentIndex: Int, depth: Int): PhoneNode {
    val r = Rect()
    n.getBoundsInScreen(r)
    return PhoneNode(
        snapshotId = snapshotId,
        seq = seq,
        parentIndex = parentIndex,
        depth = depth,
        className = n.className?.toString().orEmpty(),
        packageName = n.packageName?.toString().orEmpty(),
        text = n.text?.toString().orEmpty(),
        contentDesc = n.contentDescription?.toString().orEmpty(),
        // accessibility_service_config.xml 已开 flagReportViewIds，取 resource-id 零额外权限。
        viewId = n.viewIdResourceName.orEmpty(),
        bounds = PhoneBounds(r.left, r.top, r.right, r.bottom),
        clickable = n.isClickable,
        editable = n.isEditable,
        scrollable = n.isScrollable,
        checkable = n.isCheckable,
        checked = n.isChecked,
        selected = n.isSelected,
        enabled = n.isEnabled,
        focused = n.isFocused,
        visible = n.isVisibleToUser,
    )
}

internal fun PhoneBridge.doTree(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    val started = System.currentTimeMillis()
    val params = parseTreeParams(obj)
        ?: return treeFailure(PhoneErrorCode.BadArgs, "filter must be an object; out-of-range maxNodes/maxDepth are clamped, not rejected")
    // 代次在遍历前取：nodeId 与这个代次绑定，跨代复用由调用方判 E_STALE_SNAPSHOT。
    val snapshotId = generationOf(svc)
    val snapshot = a11y(8000L) { buildTree(svc, snapshotId, params) }
        ?: return treeFailure(PhoneErrorCode.Timeout, "Screen read timed out (the accessibility thread did not return within 8s)")
    if (params.selector != null && snapshot.nodes.isEmpty()) {
        return treeFailure(PhoneErrorCode.NodeNotFound, "No node matches filter (snapshot " + snapshotId + ")")
    }
    val drift = generationOf(svc) != snapshotId
    return PhoneResult(true, treeEnvelope(snapshot, params, drift, System.currentTimeMillis() - started))
}

/** 读侧失败也走 ok=true 的 JSON 包：错误码要原样到模型手上；同时避免执行器把它当异常重试。 */
internal fun PhoneBridge.treeFailure(code: PhoneErrorCode, message: String): PhoneResult =
    PhoneResult(true, phoneErrorJson(code, message).toString())

internal fun PhoneBridge.treeEnvelope(snapshot: PhoneSnapshot, p: TreeParams, drift: Boolean, elapsedMs: Long): String {
    val dm = context.resources.displayMetrics
    val nodes = JSONArray()
    for (n in snapshot.nodes) nodes.put(nodeJson(n))
    val fg = JSONObject()
        .put("package", svcForeground()?.foregroundPackage() ?: JSONObject.NULL)
        .put("activity", svcForeground()?.foregroundActivity() ?: JSONObject.NULL)
        .put("sinceMs", svcForeground()?.foregroundSinceMs() ?: 0L)
    return JSONObject()
        .put("ok", true)
        .put("snapshotId", snapshot.snapshotId.toString())
        .put("generation", snapshot.snapshotId)
        .put(
            "screen",
            JSONObject()
                .put("width", dm.widthPixels)
                .put("height", dm.heightPixels)
                .put("density", dm.density.toDouble()),
        )
        .put("foreground", fg)
        .put("nodeCount", snapshot.nodes.size)
        .put("truncated", snapshot.truncated)
        .put("truncatedReason", snapshot.truncatedReason ?: JSONObject.NULL)
        // 遍历期间界面又变了：节点仍可用，但调用方据此决定要不要重读（T40 的 E_SNAPSHOT_DRIFT 判据）。
        .put("changedDuringRead", drift)
        .put(
            "params",
            JSONObject()
                .put("maxNodes", p.maxNodes)
                .put("maxDepth", p.maxDepth)
                .put("includeInvisible", p.includeInvisible)
                .put("onlyClickable", p.onlyClickable),
        )
        .put("nodes", nodes)
        .put("elapsedMs", elapsedMs)
        .toString()
}

internal fun PhoneBridge.svcForeground(): PhoneAccessibilityService? = PhoneAccessibilityService.current()

/** doTree 拿到的是基类句柄（旧动作签名统一用 AccessibilityService），代次只在本服务的子类上。 */
internal fun PhoneBridge.generationOf(svc: AccessibilityService): Long =
    (svc as? PhoneAccessibilityService)?.currentGeneration() ?: 0L

internal fun PhoneBridge.nodeJson(n: PhoneNode): JSONObject = JSONObject()
    .put("nodeId", n.nodeId)
    .put("parentId", n.parentId ?: JSONObject.NULL)
    .put("depth", n.depth)
    .put("className", n.className)
    .put("package", n.packageName)
    .put("text", n.text)
    .put("desc", n.contentDesc)
    .put("viewId", n.viewId)
    .put(
        "bounds",
        JSONObject()
            .put("left", n.bounds.left)
            .put("top", n.bounds.top)
            .put("right", n.bounds.right)
            .put("bottom", n.bounds.bottom),
    )
    .put("clickable", n.clickable)
    .put("editable", n.editable)
    .put("scrollable", n.scrollable)
    .put("checkable", n.checkable)
    .put("checked", n.checked)
    .put("selected", n.selected)
    .put("enabled", n.enabled)
    .put("focused", n.focused)
    .put("visible", n.visible)
