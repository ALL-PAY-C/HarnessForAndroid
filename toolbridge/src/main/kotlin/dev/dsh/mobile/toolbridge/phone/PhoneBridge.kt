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
import dev.dsh.mobile.toolbridge.ShizukuChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            else -> PhoneResult(false, "", "未知 action: " + action + "（支持 tap/swipe/type/back/home/recents/readscreen/openapp/screenshot）")
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
