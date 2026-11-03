package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.dsh.mobile.toolbridge.ShizukuChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

data class PhoneResult(
    val ok: Boolean,
    val output: String,
    val error: String? = null,
)

class PhoneBridge(private val context: Context) {

    private val a11yExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phone-a11y").apply { isDaemon = true }
    }

    fun describeForModel(): String = buildString {
        append("phone 工具：直接操控手机（无需 root/ADB，需先在 设置→无障碍 开启「DSH Mobile 手机控制」）。")
        append("动作 action：tap（点击，用 fx/fy 分数坐标最稳，或 x/y 像素；带 query 按文本点；带 durationMs 变长按）")
        append("、swipe（滑动，起点 fx/fy 或 x/y，终点 fx2/fy2 或 x2/y2，durationMs 控制快慢）")
        append("、type（往聚焦输入框写字，参数 text）、back/home/recents（系统键）")
        append("、readscreen（读当前屏幕文本，紧凑列表，maxItems 限条目）")
        append("、openapp（package 包名或 query 应用名模糊匹配）、screenshot（Android 11+ 存本地文件）。")
        append("坐标优先用 fx/fy（0-1 相对屏幕），模型看的是缩放截图，绝对像素容易点偏。")
        append("读屏输出每行 text|desc|cx,cy|clickable|class。")
        append("结构化读屏（viewId/bounds/节点身份/快照代次）用 phone_tree 工具。")
    }

    fun isEnabled(): Boolean {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(context, PhoneAccessibilityService::class.java)
        val targets = listOf(cn.flattenToString(), cn.flattenToShortString(), cn.className)
        return raw.split(':').any { s -> targets.any { it.equals(s, ignoreCase = true) } }
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun overlaySettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:" + context.packageName),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isOverlayEnabled(): Boolean = Settings.canDrawOverlays(context)

    /** 权限快照：系统是否勾选 + 服务是否连上 + 悬浮窗是否授权，页面与悬浮窗共用同一份判定。 */
    fun permissionState(): PhonePermissionState = PhonePermissionState(
        serviceEnabled = isEnabled(),
        serviceBound = PhoneAccessibilityService.isRunning(),
        overlayGranted = isOverlayEnabled(),
    )

    // ---- T6「权限与后台」：只读状态 + 打开系统入口，绝不替用户开启 ----

    /** 通知使用权：读 Secure 里的已授权组件列表，含本包即已授权（不需要额外依赖）。 */
    fun isNotificationListenerEnabled(): Boolean {
        val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return raw.split(':').any { it.startsWith(context.packageName) }
    }

    fun notificationListenerIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 电池优化白名单：已忽略 = 后台不被冻结。 */
    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun batteryOptimizationIntent(): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        android.net.Uri.parse("package:" + context.packageName),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Shizuku 状态：装没装（包名）+ 服务活着没（反射 pingBinder）+ 授没授权。 */
    fun shizukuStatus(): ShizukuStatus {
        val installed = runCatching {
            context.packageManager.getPackageInfo(ShizukuStatus.PACKAGE, 0)
            true
        }.getOrDefault(false)
        val channel = ShizukuChannel()
        return ShizukuStatus.of(installed, channel.binderAlive(), channel.permissionGranted())
    }

    suspend fun exec(json: String): PhoneResult = withContext(Dispatchers.IO) {
        val result = execBlocking(json)
        recordOutcome(json, result)
        result
    }

    /** 每次下发都留一条可观察记录，页面的动作列表与悬浮窗状态都吃这条流。 */
    private fun recordOutcome(json: String, result: PhoneResult) {
        val action = runCatching { JSONObject(json).optString("action", "") }.getOrNull().orEmpty()
        if (action.isEmpty()) return
        val text = (if (result.ok) result.output else result.error ?: result.output)
            .lineSequence().firstOrNull().orEmpty().take(140)
        PhoneActionLog.record(action, result.ok, text)
            .also { PhoneAccessibilityService.current()?.onActionRecorded(it) }
    }

    private fun execBlocking(json: String): PhoneResult {
        val svc = PhoneAccessibilityService.current()
        if (svc == null) {
            // 读侧新工具要带错误码；旧 9 个动作的失败文案保持逐字符不变。
            if (isTreeAction(json)) {
                return treeFailure(PhoneErrorCode.A11yOff, "无障碍服务未开启")
            }
            return PhoneResult(
                ok = false,
                output = "",
                error = "手机控制不可用：无障碍服务未开启。请到 设置 → 无障碍 → DSH Mobile 手机控制 开启后重试",
            )
        }
        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return PhoneResult(false, "", "参数不是合法 JSON：" + t.message)
        }
        return when (val action = obj.optString("action", "")) {
            "tap" -> doTap(svc, obj)
            "swipe" -> doSwipe(svc, obj)
            "type" -> doType(svc, obj)
            "back" -> doGlobal(svc, AccessibilityService.GLOBAL_ACTION_BACK, "返回")
            "home" -> doGlobal(svc, AccessibilityService.GLOBAL_ACTION_HOME, "回主页")
            "recents" -> doGlobal(svc, AccessibilityService.GLOBAL_ACTION_RECENTS, "最近任务")
            "readscreen" -> doReadScreen(svc, obj)
            "openapp" -> doOpenApp(obj)
            "screenshot" -> doScreenshot(svc)
            // T4.1 读侧：结构化节点树。工具面是独立的 phone_tree（见 LocalEngineTransport），
            // 但执行走这条同一通道，core:protocol 不必反向依赖 toolbridge。
            ACTION_TREE -> doTree(svc, obj)
            else -> PhoneResult(false, "", "未知 action: " + action + "（支持 tap/swipe/type/back/home/recents/readscreen/openapp/screenshot/tree）")
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun point(obj: JSONObject, keyX: String, keyY: String, keyFx: String, keyFy: String): Pair<Int, Int>? {
        val (w, h) = screenSize()
        val fx = obj.optDouble(keyFx, -1.0)
        val fy = obj.optDouble(keyFy, -1.0)
        if (fx in 0.0..1.0 && fy in 0.0..1.0) return (fx * w).roundToInt() to (fy * h).roundToInt()
        if (obj.has(keyX) && obj.has(keyY)) return obj.getInt(keyX) to obj.getInt(keyY)
        return null
    }

    private fun doTap(svc: AccessibilityService, obj: JSONObject): PhoneResult {
        val query = obj.optString("query", "").trim()
        if (query.isNotEmpty()) return tapByQuery(svc, query)
        val p = point(obj, "x", "y", "fx", "fy")
            ?: return PhoneResult(false, "", "tap 需要 query 或坐标（fx/fy 或 x/y）")
        val duration = obj.optLong("durationMs", 60L).coerceIn(20L, 5000L)
        val ok = gesture(svc, Path().apply { moveTo(p.first.toFloat(), p.second.toFloat()) }, duration)
        return if (ok) PhoneResult(true, "已点击 (" + p.first + "," + p.second + ")，时长 " + duration + "ms")
        else PhoneResult(false, "", "dispatchGesture 未接受点击手势")
    }

    private fun doSwipe(svc: AccessibilityService, obj: JSONObject): PhoneResult {
        val a = point(obj, "x", "y", "fx", "fy")
            ?: return PhoneResult(false, "", "swipe 需要起点坐标（fx/fy 或 x/y）")
        val b = point(obj, "x2", "y2", "fx2", "fy2")
            ?: return PhoneResult(false, "", "swipe 需要终点坐标（fx2/fy2 或 x2/y2）")
        val duration = obj.optLong("durationMs", 300L).coerceIn(20L, 5000L)
        val path = Path().apply {
            moveTo(a.first.toFloat(), a.second.toFloat())
            lineTo(b.first.toFloat(), b.second.toFloat())
        }
        val ok = gesture(svc, path, duration)
        return if (ok) PhoneResult(true, "已从 (" + a.first + "," + a.second + ") 滑到 (" + b.first + "," + b.second + ")，时长 " + duration + "ms")
        else PhoneResult(false, "", "dispatchGesture 未接受滑动手势")
    }

    private fun doType(svc: AccessibilityService, obj: JSONObject): PhoneResult {
        val text = obj.optString("text", "")
        if (text.isEmpty()) return PhoneResult(false, "", "type 需要 text 参数")
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
            null -> PhoneResult(false, "", "输入超时或服务不可用")
            "NO_EDITABLE" -> PhoneResult(false, "", "找不到可输入的编辑框（可能当前界面无输入框，或输入框是 WebView/contenteditable）")
            "FAIL" -> PhoneResult(false, "", "ACTION_SET_TEXT 失败（输入框可能是 WebView/contenteditable，不支持无障碍写入）")
            else -> PhoneResult(true, "已输入 " + text.length + " 个字符")
        }
    }

    private fun doGlobal(svc: AccessibilityService, action: Int, label: String): PhoneResult {
        return if (svc.performGlobalAction(action)) PhoneResult(true, "已执行 " + label)
        else PhoneResult(false, "", label + " 失败")
    }

    private fun doReadScreen(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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
            "screen:" + w + "x" + h + "\n" + if (count == 0) "(无可读文本节点)" else out.toString().trimEnd()
        }
        return if (body != null) PhoneResult(true, body)
        else PhoneResult(false, "", "读屏超时或服务不可用")
    }

    // ---- T4.1 读侧：结构化节点树（旧 readscreen 路径不受影响，见 doReadScreen / rootsOf）----

    private data class TreeParams(
        val maxNodes: Int,
        val maxDepth: Int,
        val includeInvisible: Boolean,
        val excludeOwnPackage: Boolean,
        val packageFilter: String?,
        val onlyClickable: Boolean,
        val selector: PhoneSelector?,
    )

    /** 数值越界只夹取不报错（模型给 5000 就是想多要节点）；filter 不是对象才算参数非法。 */
    private fun parseTreeParams(obj: JSONObject): TreeParams? {
        val raw = obj.opt("filter")
        if (raw != null && raw != JSONObject.NULL && raw !is JSONObject) return null
        return TreeParams(
            maxNodes = obj.optInt("maxNodes", 400).coerceIn(1, 2000),
            maxDepth = obj.optInt("maxDepth", 6).coerceIn(1, 64),
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
    private fun treeRoots(svc: AccessibilityService, p: TreeParams): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (w in svc.windows) {
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

    private fun rootPasses(root: AccessibilityNodeInfo, p: TreeParams): Boolean {
        val pkg = root.packageName?.toString().orEmpty()
        if (p.excludeOwnPackage && pkg == context.packageName) return false
        if (p.packageFilter != null && pkg != p.packageFilter) return false
        return true
    }

    private fun buildTree(svc: AccessibilityService, snapshotId: Long, p: TreeParams): PhoneSnapshot {
        val nodes = ArrayList<PhoneNode>()
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
            if (keep) nodes.add(node)
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
        return PhoneSnapshot(snapshotId, nodes, truncated, reason)
    }

    private fun toNode(n: AccessibilityNodeInfo, snapshotId: Long, seq: Int, parentIndex: Int, depth: Int): PhoneNode {
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

    private fun doTree(svc: AccessibilityService, obj: JSONObject): PhoneResult {
        val started = System.currentTimeMillis()
        val params = parseTreeParams(obj)
            ?: return treeFailure(PhoneErrorCode.BadArgs, "filter 必须是对象；maxNodes/maxDepth 越界会被夹取而非报错")
        // 代次在遍历前取：nodeId 与这个代次绑定，跨代复用由调用方判 E_STALE_SNAPSHOT。
        val snapshotId = generationOf(svc)
        val snapshot = a11y(8000L) { buildTree(svc, snapshotId, params) }
            ?: return treeFailure(PhoneErrorCode.Timeout, "读屏超时（无障碍线程 8s 未返回）")
        if (params.selector != null && snapshot.nodes.isEmpty()) {
            return treeFailure(PhoneErrorCode.NodeNotFound, "没有匹配 filter 的节点（快照 " + snapshotId + "）")
        }
        val drift = generationOf(svc) != snapshotId
        return PhoneResult(true, treeEnvelope(snapshot, params, drift, System.currentTimeMillis() - started))
    }

    /** 读侧失败也走 ok=true 的 JSON 包：错误码要原样到模型手上；同时避免执行器把它当异常重试。 */
    private fun treeFailure(code: PhoneErrorCode, message: String): PhoneResult =
        PhoneResult(true, phoneErrorJson(code, message).toString())

    private fun treeEnvelope(snapshot: PhoneSnapshot, p: TreeParams, drift: Boolean, elapsedMs: Long): String {
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

    private fun svcForeground(): PhoneAccessibilityService? = PhoneAccessibilityService.current()

    /** doTree 拿到的是基类句柄（旧动作签名统一用 AccessibilityService），代次只在本服务的子类上。 */
    private fun generationOf(svc: AccessibilityService): Long =
        (svc as? PhoneAccessibilityService)?.currentGeneration() ?: 0L

    private fun nodeJson(n: PhoneNode): JSONObject = JSONObject()
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

    private fun doOpenApp(obj: JSONObject): PhoneResult {
        val pkg = obj.optString("package", "").trim()
        val query = obj.optString("query", "").trim()
        val target = when {
            pkg.isNotEmpty() -> pkg
            query.isNotEmpty() -> resolveByQuery(query)
            else -> return PhoneResult(false, "", "openapp 需要 package 或 query 参数")
        }
        if (target.isEmpty()) return PhoneResult(false, "", "没找到匹配应用：" + query)
        val launch = context.packageManager.getLaunchIntentForPackage(target)
            ?: return PhoneResult(false, "", "无法启动应用：" + target + "（找不到可启动的 Activity）")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            PhoneResult(true, "已启动 " + target)
        } catch (t: Throwable) {
            PhoneResult(false, "", "启动 " + target + " 失败：" + t.message)
        }
    }

    private fun doScreenshot(svc: AccessibilityService): PhoneResult {
        if (Build.VERSION.SDK_INT < 30) {
            return PhoneResult(false, "", "截图需要 Android 11+（当前 " + Build.VERSION.SDK_INT + "），请改用 readscreen 读节点树")
        }
        val done = CountDownLatch(1)
        val result = AtomicReference<ScreenshotOutcome>(ScreenshotOutcome.Timeout)
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, a11yExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    result.set(ScreenshotOutcome.Success(screenshot))
                    done.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    result.set(ScreenshotOutcome.Failure(errorCode))
                    done.countDown()
                }
            })
            if (!done.await(5000, TimeUnit.MILLISECONDS)) return PhoneResult(false, "", "截图超时")
        } catch (t: Throwable) {
            return PhoneResult(false, "", "截图失败：" + t.message)
        }
        return when (val r = result.get()) {
            is ScreenshotOutcome.Failure -> PhoneResult(false, "", "截图失败：errorCode=" + r.code + "，降级请用 readscreen 读节点树")
            is ScreenshotOutcome.Success -> {
                val bitmap = Bitmap.wrapHardwareBuffer(r.shot.hardwareBuffer, r.shot.colorSpace)
                    ?: return PhoneResult(false, "", "无法解码截图（wrapHardwareBuffer 返回 null）")
                val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
                val f = File(dir, "shot_" + System.currentTimeMillis() + ".png")
                FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                PhoneResult(true, "已保存 " + bitmap.width + "x" + bitmap.height + " → " + f.absolutePath)
            }
            else -> PhoneResult(false, "", "截图失败：无结果")
        }
    }

    private fun tapByQuery(svc: AccessibilityService, query: String): PhoneResult {
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
            outcome == null -> PhoneResult(false, "", "点击超时或服务不可用")
            outcome == "NOT_FOUND" -> PhoneResult(false, "", "没有找到文本包含「" + query + "」的节点")
            outcome.startsWith("GESTURE:") -> {
                val xy = outcome.removePrefix("GESTURE:").split(",")
                val ok = gesture(svc, Path().apply { moveTo(xy[0].toFloat(), xy[1].toFloat()) }, 60L)
                if (ok) PhoneResult(true, "已点击文本「" + query + "」（坐标 " + xy[0] + "," + xy[1] + "）")
                else PhoneResult(false, "", "节点 ACTION_CLICK 失败，手势点击也失败")
            }
            else -> PhoneResult(true, "已点击文本「" + query + "」")
        }
    }

    private fun resolveByQuery(query: String): String {
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

    private fun findEditable(svc: AccessibilityService): AccessibilityNodeInfo? {
        val roots = rootsOf(svc)
        for (root in roots) findNode(root, pred = { it.isEditable && it.isFocused })?.let { return it }
        for (root in roots) findNode(root, pred = { it.isEditable })?.let { return it }
        return null
    }

    private fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean, maxNodes: Int = 4000): AccessibilityNodeInfo? {
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

    private fun rootsOf(svc: AccessibilityService): List<AccessibilityNodeInfo> {
        val wins = svc.windows
        val roots = wins.mapNotNull { it.root }
        if (roots.isNotEmpty()) return roots
        val root = svc.rootInActiveWindow
        return listOfNotNull(root)
    }

    private fun isTreeAction(json: String): Boolean =
        runCatching { JSONObject(json).optString("action", "") == ACTION_TREE }.getOrDefault(false)

    private fun <T> a11y(timeoutMs: Long, block: () -> T): T? =
        try {
            a11yExecutor.submit<T> { block() }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            null
        }

    private fun gesture(svc: AccessibilityService, path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
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

    companion object {
        /** 读侧动作名；工具面叫 phone_tree，执行仍走 phone 通道（见 LocalEngineTransport 的注册）。 */
        private const val ACTION_TREE = "tree"

        @Volatile
        private var serviceBound = false

        fun onServiceReady() { serviceBound = true }

        fun onServiceLost() { serviceBound = false }
    }

    private sealed class ScreenshotOutcome {
        data class Success(val shot: AccessibilityService.ScreenshotResult) : ScreenshotOutcome()
        data class Failure(val code: Int) : ScreenshotOutcome()
        data object Timeout : ScreenshotOutcome()
    }
}
