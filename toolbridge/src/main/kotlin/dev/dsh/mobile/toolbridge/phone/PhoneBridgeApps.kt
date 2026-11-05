package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject


internal fun PhoneBridge.doCurrentApp(): PhoneResult {
    val started = System.currentTimeMillis()
    val phone = svcForeground()
    val pkg = phone?.foregroundPackage() ?: ""
    val activity = phone?.foregroundActivity() ?: ""
    val sinceMs = phone?.foregroundSinceMs() ?: 0L
    var label = ""
    if (pkg.isNotEmpty()) {
        label = try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            ai.loadLabel(context.packageManager).toString()
        } catch (e: Exception) {
            ""
        }
    }
    return okEnvelope(
        started,
        JSONObject()
            .put("package", pkg)
            .put("activity", activity)
            .put("label", label)
            .put("sinceMs", sinceMs)
            .put("isOwnApp", pkg.isNotEmpty() && pkg == context.packageName),
    )
}

/** apps：queryIntentActivities(MAIN/LAUNCHER) 去重后按 package 排序，分页返回。 */
internal fun PhoneBridge.doApps(obj: JSONObject): PhoneResult {
    val started = System.currentTimeMillis()
    val includeSystem = obj.optBoolean("includeSystem", false)
    val query = obj.optString("query", "").trim()
    val limit = obj.optInt("limit", 120).coerceIn(1, 500)
    val offset = obj.optInt("offset", 0).coerceAtLeast(0)
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
    val seen = LinkedHashMap<String, ApplicationInfo>()
    for (ri in resolved) {
        val ai = ri.activityInfo?.applicationInfo ?: continue
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isSystem && !includeSystem) continue
        if (seen.containsKey(ai.packageName)) continue
        seen[ai.packageName] = ai
    }
    val all = seen.values.sortedBy { it.packageName }
    val filtered = if (query.isEmpty()) all else all.filter {
        val label = runCatching { it.loadLabel(pm).toString() }.getOrDefault("")
        it.packageName.contains(query, ignoreCase = true) || label.contains(query, ignoreCase = true)
    }
    val page = filtered.drop(offset).take(limit)
    val apps = JSONArray()
    for (ai in page) {
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault("")
        val launchable = pm.getLaunchIntentForPackage(ai.packageName) != null
        apps.put(
            JSONObject()
                .put("package", ai.packageName)
                .put("label", label)
                .put("isSystem", isSystem)
                .put("launchable", launchable),
        )
    }
    return okEnvelope(
        started,
        JSONObject()
            .put("count", apps.length())
            .put("total", filtered.size)
            .put("offset", offset)
            .put("limit", limit)
            .put("query", query)
            .put("includeSystem", includeSystem)
            .put("truncated", offset + apps.length() < filtered.size)
            .put("apps", apps),
    )
}

/** sequence：一次下发多步，单步失败即停（不回滚），把第几步与错误码回给模型。 */
internal suspend fun PhoneBridge.doSequence(svc: AccessibilityService, obj: JSONObject): PhoneResult {
    val started = System.currentTimeMillis()
    val steps = when (val r = PhoneSequence.parse(obj.opt("steps"))) {
        is PhoneStepsResult.Bad -> return failResult(PhoneErrorCode.BadArgs, r.message)
        is PhoneStepsResult.Ok -> r.steps
    }
    val stopOnError = obj.optBoolean("stopOnError", true)
    val dryRun = obj.optBoolean("dryRun", false)
    val outcomes = ArrayList<PhoneStepOutcome>(steps.size)
    var aborted = false
    var abortCode: String? = null
    for (step in steps) {
        if (dryRun) {
            outcomes.add(PhoneStepOutcome(step.index, step.action, true, "", "", 0L))
            continue
        }
        val stepStarted = System.currentTimeMillis()
        val (ok, code, message) = classifyStep(svc, step.action, step.toJson())
        outcomes.add(PhoneStepOutcome(step.index, step.action, ok, code, message, System.currentTimeMillis() - stepStarted))
        if (!ok) {
            aborted = true
            abortCode = code
            if (stopOnError) break
        }
        if (step.waitAfterMs > 0) cancellableDelay(step.waitAfterMs)
    }
    return PhoneResult(
        true,
        PhoneSequence.envelope(outcomes, steps.size, aborted, abortCode, System.currentTimeMillis() - started).toString(),
    )
}

/** 单步判定：旧动作看 PhoneResult.ok，新动作看内层错误包（外层恒 ok）。 */
internal suspend fun PhoneBridge.classifyStep(svc: AccessibilityService, action: String, json: String): Triple<Boolean, String, String> {
    val result = execBlocking(json)
    val payload = runCatching { JSONObject(result.output) }.getOrNull()
    if (payload != null && payload.has("code")) {
        val code = payload.optString("code", "")
        // 把候选节点摘要带进步骤消息：sequence 只回一步的 code/message，
        // 不带候选的话模型看到「命中 2 个节点」也不知道该给哪个 nodeId。
        return Triple(false, code, payload.optString("message", "") + candidatesHint(payload))
    }
    return if (result.ok) Triple(true, "", "")
    else Triple(false, PhoneErrorCode.ActionRejected.code, result.error ?: "")
}

/** 候选节点压缩成一行：「text@cx,cy#nodeId」，最多 3 个；nodeId 可原样回填到下一步的 target。 */
internal fun PhoneBridge.candidatesHint(payload: JSONObject): String {
    val arr = payload.optJSONArray("candidates") ?: return ""
    if (arr.length() == 0) return ""
    val parts = ArrayList<String>()
    for (i in 0 until minOf(arr.length(), 3)) {
        val c = arr.optJSONObject(i) ?: continue
        val label = c.optString("text").ifEmpty { c.optString("desc") }.ifEmpty { c.optString("viewId") }
            .ifEmpty { c.optString("className") }
        val b = c.optJSONObject("bounds")
        val pos = if (b != null) {
            "@" + ((b.optInt("left") + b.optInt("right")) / 2) + "," +
                ((b.optInt("top") + b.optInt("bottom")) / 2)
        } else {
            ""
        }
        parts.add(label.take(24) + pos + "#" + c.optString("nodeId"))
    }
    if (parts.isEmpty()) return ""
    val more = if (arr.length() > parts.size) " and " + arr.length() + " more" else ""
    return "; candidates: " + parts.joinToString(" | ") + more
}

internal fun PhoneBridge.doOpenApp(obj: JSONObject): PhoneResult {
    val pkg = obj.optString("package", "").trim()
    val query = obj.optString("query", "").trim()
    val target = when {
        pkg.isNotEmpty() -> pkg
        query.isNotEmpty() -> resolveByQuery(query)
        else -> return PhoneResult(false, "", "openapp requires the package or query parameter")
    }
    if (target.isEmpty()) return PhoneResult(false, "", "No matching app: " + query)
    val launch = context.packageManager.getLaunchIntentForPackage(target)
        ?: return PhoneResult(false, "", "Cannot launch app: " + target + " (no launchable Activity found)")
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(launch)
        PhoneResult(true, "Launched " + target)
    } catch (t: Throwable) {
        PhoneResult(false, "", "Launch of " + target + " failed: " + t.message)
    }
}

internal fun PhoneBridge.tapByQuery(svc: AccessibilityService, query: String): PhoneResult {
    val outcome = a11y(6000) {
        var leaf: AccessibilityNodeInfo? = null
        outer@ for (root in rootsOf(svc)) {
            val n = findNode(root, pred = { node ->
                val t = node.text?.toString().orEmpty()
                val d = node.contentDescription?.toString().orEmpty()
                t.contains(query, true) || d.contains(query, true)
            })
            if (n != null) { leaf = n; break@outer }
        }
        val l = leaf
        when {
            l == null -> "NOT_FOUND"
            else -> {
                var target = l
                var depth = 0
                while (target != null && !target.isClickable && depth < 6) {
                    target = target.parent
                    depth++
                }
                val clickable = target ?: l
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "OK"
                else {
                    val r = Rect()
                    clickable.getBoundsInScreen(r)
                    "GESTURE:" + r.centerX() + "," + r.centerY()
                }
            }
        }
    }
    return when {
        outcome == null -> PhoneResult(false, "", "Tap timed out or the service is unavailable")
        outcome == "NOT_FOUND" -> PhoneResult(false, "", "No node whose text contains '" + query + "'")
        outcome.startsWith("GESTURE:") -> {
            val xy = outcome.removePrefix("GESTURE:").split(",")
            val ok = gesture(svc, Path().apply { moveTo(xy[0].toFloat(), xy[1].toFloat()) }, 60L)
            if (ok) PhoneResult(true, "Tapped text '" + query + "' at (" + xy[0] + "," + xy[1] + ")")
            else PhoneResult(false, "", "Node ACTION_CLICK failed and the gesture tap failed too")
        }
        else -> PhoneResult(true, "Tapped text '" + query + "'")
    }
}

internal fun PhoneBridge.resolveByQuery(query: String): String {
    val q = query.lowercase()
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(intent, 0)
    var exact: String? = null
    var partial: String? = null
    for (ri in apps) {
        val pkg = ri.activityInfo.packageName
        val label = ri.loadLabel(pm).toString()
        if (label.lowercase() == q || pkg.lowercase() == q) exact = pkg
        else if ((label.lowercase().contains(q) || pkg.lowercase().contains(q)) && partial == null) partial = pkg
    }
    return exact ?: partial ?: ""
}

internal fun PhoneBridge.findEditable(svc: AccessibilityService): AccessibilityNodeInfo? {
    val roots = rootsOf(svc)
    for (root in roots) findNode(root, pred = { it.isEditable && it.isFocused })?.let { return it }
    for (root in roots) findNode(root, pred = { it.isEditable })?.let { return it }
    return null
}

internal fun PhoneBridge.findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean, maxNodes: Int = 4000): AccessibilityNodeInfo? {
    val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var visited = 0
    while (queue.isNotEmpty() && visited < maxNodes) {
        val n = queue.removeFirst()
        visited++
        if (pred(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
    }
    return null
}

internal fun PhoneBridge.rootsOf(svc: AccessibilityService): List<AccessibilityNodeInfo> {
    val wins = svc.windows
    val roots = wins.mapNotNull { it.root }
    if (roots.isNotEmpty()) return roots
    val root = svc.rootInActiveWindow
    return listOfNotNull(root)
}
