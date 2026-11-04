package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.dsh.mobile.core.common.device.CapabilityActions
import dev.dsh.mobile.toolbridge.ShizukuChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

data class PhoneResult(
    val ok: Boolean,
    val output: String,
    val error: String? = null,
    /** screenshot 专用：这张图是否已登记进「下一轮 user 消息」的注入队列；其他动作恒为 null。 */
    val imageQueued: Boolean? = null,
    /** [imageQueued] = false 时的未入队原因；成功为 null。 */
    val imageQueueReason: String? = null,
)

class PhoneBridge(
    private val context: Context,
    /**
     * T4.5：六类系统能力的档位来源（SettingsStore 里存的原始串）。由 AppContainer 注入；
     * 默认空串 = 全 Ask，也就是「没配置就按最保守的来」。
     */
    private val capabilityTiers: () -> String = { "" },
) {

    /** 能力动作的准入闸门：只查运行时权限，问不问用户由 core:protocol 的 ToolPolicy 决定。 */
    private val capabilityGate = CapabilityGate(context, capabilityTiers)

    @Volatile
    private var recording = false

    private val a11yExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phone-a11y").apply { isDaemon = true }
    }

    fun describeForModel(): String = buildString {
        append("phone tool: control the phone directly (no root/ADB; first enable 'DSH Mobile phone control' under Settings → Accessibility).")
        append("action: tap (click; fx/fy fractional coords are most stable, or x/y pixels; with query it taps by text; with durationMs it long-presses)")
        append(", swipe (drag from fx/fy or x/y to fx2/fy2 or x2/y2; durationMs sets the speed)")
        append(", type (write into the focused field; param text), back/home/recents (system keys)")
        append(", readscreen (read on-screen text as a compact list; maxItems caps entries)")
        append(", openapp (package name, or fuzzy app-name query), screenshot (Android 11+; saves a local file).")
        append("Prefer fx/fy (0-1 relative to the screen): the model sees a scaled screenshot, so absolute pixels often miss.")
        append("Each readscreen line is text|desc|cx,cy|clickable|class.")
        append("For structured reads (viewId/bounds/node identity/snapshot generation) use the phone_tree tool.")
        append("Targeting always uses target (a selector object {text,textContains,desc,viewId,viewIdSuffix,className,package,index,clickable,editable,scrollable})")
        append("or nodeId (\"<snapshotId>:<seq>\" from phone_tree). A nodeId from an older generation returns E_STALE_SNAPSHOT and an ambiguous selector returns E_NODE_AMBIGUOUS; neither dispatches any gesture.")
        append("More actions: scroll (scroll a scrollable=true container; direction=forward/backward, times, until stops at a selector hit, verify checks real movement)")
        append(", wait_for (cond.type=node_present/node_absent/text_present/package_is/generation_changed; timeoutMs defaults to 3000; on timeout returns E_WAIT_TIMEOUT)")
        append(", pinch (two-finger zoom; center or centerTarget plus scale or fromRadius/toRadius), drag (from/to or fromTarget/toTarget, or a dx/dy offset)")
        append(", key (enter/delete/tab into the focused field; back/home/recents use system keys), currentapp (foreground app), apps (installed launchable apps; query/limit/offset paging)")
        append(", sequence (send a steps array in one call; stops at the first failing step and reports its index). type also accepts target, mode=set|append|clear, and perChar typing (intervalMs).")
        append("System actions (no accessibility needed, but runtime permissions; a missing permission returns E_PERMISSION_DENIED + needsPermission):")
        append("contacts (query contacts), sms_list (read the inbox), sms_send (number + text),")
        append("dial (number, dials directly), location (last known location), audio_record (record durationMs into the cache dir).")
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

    /** Shizuku 状态：装没装（包名）+ 服务活着没（pingBinder）+ 授没授权，三态由 [ShizukuChannel.status] 统一判定。 */
    fun shizukuStatus(): ShizukuStatus = ShizukuChannel(context).status()

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

    private suspend fun execBlocking(json: String): PhoneResult {
        // T4.5 六类系统能力走系统 API，不依赖无障碍，也不依赖前台界面 ——
        // 必须排在 svc 判空之前，否则没开无障碍就永远用不了这六个动作。
        capabilityBlocking(json)?.let { return it }
        val svc = PhoneAccessibilityService.current()
        if (svc == null) {
            // 读侧新工具要带错误码；旧 9 个动作的失败文案保持逐字符不变。
            if (isTreeAction(json)) {
                return treeFailure(PhoneErrorCode.A11yOff, "Accessibility service is not enabled")
            }
            // apps 只问 PackageManager，不依赖无障碍；其余 v2 动作同样回 A11yOff，但带错误码。
            if (isV2Action(json)) {
                val obj = runCatching { JSONObject(json) }.getOrNull()
                    ?: return treeFailure(PhoneErrorCode.BadArgs, "Arguments are not valid JSON")
                val name = obj.optString("action", "")
                if (name == ACTION_APPS || name == ACTION_APPS_ALIAS) return doApps(obj)
                return treeFailure(PhoneErrorCode.A11yOff, "Accessibility service is not enabled (" + name + " needs accessibility)")
            }
            return PhoneResult(
                ok = false,
                output = "",
                error = "Phone control unavailable: the accessibility service is off. Enable DSH Mobile phone control under Settings → Accessibility, then retry",
            )
        }
        val obj = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return PhoneResult(false, "", "Arguments are not valid JSON: " + t.message)
        }
        return when (val action = obj.optString("action", "")) {
            "tap" -> doTap(svc, obj)
            "swipe" -> doSwipe(svc, obj)
            "type" -> doType(svc, obj)
            "back" -> doGlobal(svc, AccessibilityService.GLOBAL_ACTION_BACK, "Back")
            "home" -> doGlobal(svc, AccessibilityService.GLOBAL_ACTION_HOME, "Home")
            "recents" -> doGlobal(svc, AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents")
            "readscreen" -> doReadScreen(svc, obj)
            "openapp" -> doOpenApp(obj)
            "screenshot" -> doScreenshot(svc)
            // T4.1 读侧：结构化节点树。工具面是独立的 phone_tree（见 LocalEngineTransport），
            // 但执行走这条同一通道，core:protocol 不必反向依赖 toolbridge。
            ACTION_TREE -> doTree(svc, obj)
            // T4.2 动作侧：新动作统一 ok=true 外壳 + 内层错误包，错误码原样到模型，
            // 也避免执行器把「业务失败」当异常重试（副作用会重放）。
            ACTION_SCROLL -> doScroll(svc, obj)
            ACTION_PINCH -> doPinch(svc, obj)
            ACTION_DRAG -> doDrag(svc, obj)
            ACTION_WAIT_FOR -> doWaitFor(svc, obj)
            ACTION_CURRENT_APP -> doCurrentApp()
            ACTION_APPS, ACTION_APPS_ALIAS -> doApps(obj)
            ACTION_SEQUENCE -> doSequence(svc, obj)
            ACTION_KEY -> doKey(svc, obj)
            else -> PhoneResult(
                false,
                "",
                "Unknown action: " + action +
                    "(supported: tap/swipe/type/back/home/recents/readscreen/openapp/screenshot/tree/" +
                    "scroll/pinch/drag/wait_for/currentapp/apps/sequence/key/" +
                    "contacts/sms_list/sms_send/dial/location/audio_record)",
            )
        }
    }

    // ---- T4.5 六类系统能力：直接调系统 API，不走无障碍 ----

    /**
     * 能力动作入口。返回 null = 不是能力动作，交回无障碍主通道。
     *
     * 缺权限不抛异常，回 E_PERMISSION_DENIED + needsPermission，模型据此提示用户去授权；
     * 系统 API 抛的异常同样翻成错误包 —— 抛出去会被执行器当可重试错误，副作用可能已经落地。
     */
    private suspend fun capabilityBlocking(json: String): PhoneResult? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val action = obj.optString("action", "")
        if (!CapabilityActions.isCapabilityAction(action)) return null
        val started = System.currentTimeMillis()
        if (!capabilityGate.granted(action)) return capabilityGate.deniedResult(action)
        return try {
            when (action) {
                CapabilityActions.CONTACTS -> doContacts(obj, started)
                CapabilityActions.SMS_LIST -> doSmsList(obj, started)
                CapabilityActions.SMS_SEND -> doSmsSend(obj, started)
                CapabilityActions.DIAL -> doDial(obj, started)
                CapabilityActions.LOCATION -> doLocation(started)
                CapabilityActions.AUDIO_RECORD -> doAudioRecord(obj, started)
                else -> null
            }
        } catch (t: Throwable) {
            // 取消必须原样上抛：吞掉 CancellationException 会把「取消」变成「继续执行」。
            if (t is CancellationException) throw t
            failResult(PhoneErrorCode.ActionRejected, action + " failed: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun doContacts(obj: JSONObject, started: Long): PhoneResult {
        val limit = obj.optInt("limit", 20).coerceIn(1, 200)
        val query = obj.optString("query", "").trim()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = if (query.isEmpty()) null else ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
        val selectionArgs = if (query.isEmpty()) null else arrayOf("%" + query + "%")
        val rows = JSONArray()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            while (rows.length() < limit && c.moveToNext()) {
                rows.put(
                    JSONObject()
                        .put("name", c.getString(0) ?: "")
                        .put("number", c.getString(1) ?: ""),
                )
            }
        }
        return okEnvelope(started, JSONObject().put("contacts", rows).put("count", rows.length()))
    }

    private fun doSmsList(obj: JSONObject, started: Long): PhoneResult {
        val limit = obj.optInt("limit", 10).coerceIn(1, 100)
        val rows = JSONArray()
        context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"),
            null,
            null,
            "date DESC",
        )?.use { c ->
            while (rows.length() < limit && c.moveToNext()) {
                rows.put(
                    JSONObject()
                        .put("address", c.getString(0) ?: "")
                        .put("body", c.getString(1) ?: "")
                        .put("date", c.getLong(2)),
                )
            }
        }
        return okEnvelope(started, JSONObject().put("messages", rows).put("count", rows.length()))
    }

    private fun doSmsSend(obj: JSONObject, started: Long): PhoneResult {
        val number = obj.optString("number", "").trim()
        val text = obj.optString("text", "")
        if (number.isEmpty()) return failResult(PhoneErrorCode.BadArgs, "sms_send requires number")
        if (text.isEmpty()) return failResult(PhoneErrorCode.BadArgs, "sms_send requires text")
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return failResult(PhoneErrorCode.ActionRejected, "SmsManager unavailable")
        val parts = manager.divideMessage(text)
        manager.sendMultipartTextMessage(number, null, parts, null, null)
        return okEnvelope(started, JSONObject().put("sent", true).put("number", number).put("parts", parts.size))
    }

    private fun doDial(obj: JSONObject, started: Long): PhoneResult {
        val number = obj.optString("number", "").trim()
        if (number.isEmpty()) return failResult(PhoneErrorCode.BadArgs, "dial requires number")
        context.startActivity(
            Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return okEnvelope(started, JSONObject().put("dialed", number))
    }

    private fun doLocation(started: Long): PhoneResult {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return failResult(PhoneErrorCode.ActionRejected, "LocationManager unavailable")
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val best = providers.mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return failResult(PhoneErrorCode.ActionRejected, "No recent location available (let the system obtain a fix first)")
        return okEnvelope(
            started,
            JSONObject()
                .put("lat", best.latitude)
                .put("lon", best.longitude)
                .put("accuracyM", best.accuracy.toDouble())
                .put("provider", best.provider ?: "")
                .put("ageMs", System.currentTimeMillis() - best.time),
        )
    }

    private suspend fun doAudioRecord(obj: JSONObject, started: Long): PhoneResult {
        if (recording) return failResult(PhoneErrorCode.Busy, "Already recording; wait for the current take to finish")
        val durationMs = obj.optLong("durationMs", 3000L).coerceIn(500L, 15_000L)
        val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
        val out = File(dir, "rec-" + System.currentTimeMillis() + ".m4a")
        val recorder = MediaRecorder()
        recording = true
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(out.absolutePath)
            recorder.prepare()
            recorder.start()
            cancellableDelay(durationMs)
            recorder.stop()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            runCatching { recorder.reset() }
            return failResult(PhoneErrorCode.ArtifactWrite, "Recording failed: " + (t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { recorder.release() }
            recording = false
        }
        if (!out.exists() || out.length() == 0L) return failResult(PhoneErrorCode.ArtifactWrite, "Recording file is empty")
        return okEnvelope(
            started,
            JSONObject().put("path", out.absolutePath).put("bytes", out.length()).put("durationMs", durationMs),
        )
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

    private fun doSwipe(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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

    private suspend fun doType(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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

    private fun doGlobal(svc: AccessibilityService, action: Int, label: String): PhoneResult {
        return if (svc.performGlobalAction(action)) PhoneResult(true, "Executed " + label)
        else PhoneResult(false, "", label + " failed")
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
            "screen:" + w + "x" + h + "\n" + if (count == 0) "(no readable text nodes)" else out.toString().trimEnd()
        }
        return if (body != null) PhoneResult(true, body)
        else PhoneResult(false, "", "Screen read timed out or the service is unavailable")
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
    private fun treeRoots(svc: AccessibilityService, p: TreeParams): List<AccessibilityNodeInfo> {
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

    private fun rootPasses(root: AccessibilityNodeInfo, p: TreeParams): Boolean {
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
    private class Walked(
        val nodes: List<PhoneNode>,
        val infos: List<AccessibilityNodeInfo>,
        val truncated: Boolean,
        val reason: String?,
    )

    private fun buildTree(svc: AccessibilityService, snapshotId: Long, p: TreeParams): PhoneSnapshot {
        val walked = walkTree(svc, snapshotId, p, wantInfo = false)
        return PhoneSnapshot(snapshotId, walked.nodes, walked.truncated, walked.reason)
    }

    private fun walkTree(svc: AccessibilityService, snapshotId: Long, p: TreeParams, wantInfo: Boolean): Walked {
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

    // ─────────────────────────────────────────────────────────────────────
    // T4.2 动作侧：选择器 / nodeId 定位 + 新原语（scroll/pinch/drag/wait_for/
    // currentapp/apps/sequence/key，以及 tap/type/swipe 的新参数形态）
    //
    // 统一约定：新动作回 PhoneResult(ok = true)，业务成败与错误码放进内层错误包。
    // 这样错误码原样到模型，执行器也不会把「业务失败」当异常重试（副作用会被重放）。
    // 旧 9 个动作与 tree 的方法体、返回串一个字都没动。
    // ─────────────────────────────────────────────────────────────────────

    /** 活树定位：Hit 带的是 walkTree 的下标（infos 与 nodes 同序），Fail 带组装好的错误包。 */
    private sealed interface LiveResolve {
        class Hit(val walked: Walked, val index: Int) : LiveResolve

        class Fail(val payload: JSONObject) : LiveResolve
    }

    /** 动作侧读树：与 phone_tree 同一个 walkTree、同一套过滤与上限，所以 seq 与它一致。 */
    private fun readActionTree(svc: AccessibilityService, p: TreeParams): Walked? =
        a11y(8_000L) { walkTree(svc, generationOf(svc), p, wantInfo = true) }

    private fun resolveLive(walked: Walked, spec: PhoneTargetSpec, generation: Long): LiveResolve =
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

    private fun ambiguousPayload(r: PhoneResolveResult.Ambiguous): JSONObject {
        val candidates = JSONArray()
        for (n in r.candidates) candidates.put(nodeJson(n))
        return errorEnvelope(
            PhoneErrorCode.NodeAmbiguous,
            "Matched " + r.count + " nodes; add index or use a more precise selector (no action was dispatched)",
            effectLanded = false,
        ).put("count", r.count).put("candidates", candidates)
    }

    private fun errorEnvelope(code: PhoneErrorCode, message: String, effectLanded: Boolean? = null): JSONObject =
        if (effectLanded == null) phoneErrorJson(code, message) else phoneErrorJson(code, message, effectLanded)

    private fun failResult(code: PhoneErrorCode, message: String, effectLanded: Boolean? = null): PhoneResult =
        PhoneResult(true, errorEnvelope(code, message, effectLanded).toString())

    private fun okEnvelope(started: Long, body: JSONObject): PhoneResult =
        PhoneResult(true, body.put("ok", true).put("elapsedMs", System.currentTimeMillis() - started).toString())

    private fun boundsJson(b: PhoneBounds): JSONObject = JSONObject()
        .put("left", b.left).put("top", b.top).put("right", b.right).put("bottom", b.bottom)
        .put("cx", b.centerX).put("cy", b.centerY)

    private fun pointJson(p: Pair<Int, Int>): JSONObject = JSONObject().put("x", p.first).put("y", p.second)

    private fun pointOf(obj: JSONObject, keyX: String, keyY: String): Pair<Int, Int>? {
        if (!obj.has(keyX) || !obj.has(keyY)) return null
        val x = obj.optInt(keyX, -1)
        val y = obj.optInt(keyY, -1)
        if (x < 0 || y < 0) return null
        return x to y
    }

    private fun tapPath(b: PhoneBounds): Path =
        Path().apply { moveTo(b.centerX.toFloat(), b.centerY.toFloat()) }

    private fun setTextOn(info: AccessibilityNodeInfo, text: String): Boolean =
        info.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )

    /** 焦点输入框：优先真焦点，退而求其次找第一个可编辑节点（它们对 IME 动作才有效）。 */
    private fun focusedEditableOrNull(svc: AccessibilityService): AccessibilityNodeInfo? {
        val focus = a11y(2_000L) { svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        if (focus != null && focus.isEditable) return focus
        return findEditable(svc)
    }

    /** tap 的新路径：选择器或 nodeId。命中自身不可点就上溯最近可点祖先（列表行靠这个才点得动）。 */
    private fun doTapTarget(svc: AccessibilityService, obj: JSONObject, spec: PhoneTargetSpec): PhoneResult {
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
    private fun doSwipeTarget(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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
    private fun resolvePointOn(walked: Walked, raw: Any?, generation: Long): Pair<Int, Int>? {
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
    private suspend fun doTypeV2(svc: AccessibilityService, obj: JSONObject, spec: PhoneTargetSpec): PhoneResult {
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
    private fun doKey(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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

    /** 滚动目标点：容器可滚就在容器里滑，否则在命中节点里滑（两种都真派发手势）。 */
    private fun gestureScroll(svc: AccessibilityService, b: PhoneBounds, forward: Boolean): Boolean {
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
    private suspend fun doScroll(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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
    private fun doPinch(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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
    private fun doDrag(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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
    private fun dragPoints(
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
    private suspend fun doWaitFor(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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
    private suspend fun awaitGenerationChange(
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

    private fun doCurrentApp(): PhoneResult {
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
    private fun doApps(obj: JSONObject): PhoneResult {
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
    private suspend fun doSequence(svc: AccessibilityService, obj: JSONObject): PhoneResult {
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
    private suspend fun classifyStep(svc: AccessibilityService, action: String, json: String): Triple<Boolean, String, String> {
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
    private fun candidatesHint(payload: JSONObject): String {
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

    private fun doOpenApp(obj: JSONObject): PhoneResult {
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

    private fun doScreenshot(svc: AccessibilityService): PhoneResult {
        if (Build.VERSION.SDK_INT < 30) {
            return PhoneResult(false, "", "Screenshot requires Android 11+ (current " + Build.VERSION.SDK_INT + "); use readscreen to read the node tree instead")
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
            if (!done.await(5000, TimeUnit.MILLISECONDS)) return PhoneResult(false, "", "Screenshot timed out")
        } catch (t: Throwable) {
            return PhoneResult(false, "", "Screenshot failed: " + t.message)
        }
        return when (val r = result.get()) {
            is ScreenshotOutcome.Failure -> PhoneResult(false, "", "Screenshot failed: errorCode=" + r.code + "; fall back to readscreen to read the node tree")
            is ScreenshotOutcome.Success -> {
                val bitmap = Bitmap.wrapHardwareBuffer(r.shot.hardwareBuffer, r.shot.colorSpace)
                    ?: return PhoneResult(false, "", "Cannot decode the screenshot (wrapHardwareBuffer returned null)")
                val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
                val f = File(dir, "shot_" + System.currentTimeMillis() + ".png")
                FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                // 回图像：role=tool 放不进 image_url，先登记，下一次模型请求把它并进 user 消息。
                val queued = PhoneImageQueue.enqueue(f.absolutePath, "image/png", f.length())
                val out = buildString {
                    // 第一行逐字节保持旧文案（悬浮窗/动作日志只取第一行）。
                    append("Saved ").append(bitmap.width).append("x").append(bitmap.height).append(" → ").append(f.absolutePath)
                    append("\nimageQueued: ").append(queued.queued)
                    if (!queued.queued) append(" (not queued: ").append(queued.reason ?: "unknown").append(")")
                }
                PhoneResult(true, out, imageQueued = queued.queued, imageQueueReason = queued.reason)
            }
            else -> PhoneResult(false, "", "Screenshot failed: no result")
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

    private fun isV2Action(json: String): Boolean =
        runCatching { JSONObject(json).optString("action", "") in V2_ACTIONS }.getOrDefault(false)

    private fun <T> a11y(timeoutMs: Long, block: () -> T): T? =
        try {
            a11yExecutor.submit<T> { block() }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            null
        }

    private fun gesture(svc: AccessibilityService, path: Path, durationMs: Long): Boolean =
        gestureStrokes(svc, listOf(GestureDescription.StrokeDescription(path, 0L, durationMs)))

    /** 多指/分段手势的唯一入口：pinch 两指、drag 是「hold 段 + 续段」一次下发。 */
    private fun gestureStrokes(svc: AccessibilityService, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        if (strokes.isEmpty()) return false
        val builder = GestureDescription.Builder()
        for (stroke in strokes) builder.addStroke(stroke)
        return dispatchGesture(svc, builder.build())
    }

    private fun dispatchGesture(svc: AccessibilityService, gesture: GestureDescription): Boolean {
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

        // T4.2 动作侧动作名。工具面（LocalEngineTransport.toolsJson）与 phoneHelp 必须与这里同步。
        private const val ACTION_SCROLL = "scroll"
        private const val ACTION_PINCH = "pinch"
        private const val ACTION_DRAG = "drag"
        private const val ACTION_WAIT_FOR = "wait_for"
        private const val ACTION_CURRENT_APP = "currentapp"
        private const val ACTION_APPS = "apps"
        private const val ACTION_APPS_ALIAS = "listapps"
        private const val ACTION_SEQUENCE = "sequence"
        private const val ACTION_KEY = "key"

        /** 走 ok=true + 错误包的 v2 动作集合；apps 也在内（它只是不需要无障碍而已）。 */
        private val V2_ACTIONS: Set<String> = setOf(
            ACTION_TREE, ACTION_SCROLL, ACTION_PINCH, ACTION_DRAG, ACTION_WAIT_FOR,
            ACTION_CURRENT_APP, ACTION_APPS, ACTION_APPS_ALIAS, ACTION_SEQUENCE, ACTION_KEY,
        )

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

/**
 * 协程可取消的等待：替代阻塞式线程睡眠。
 *
 * 取消时 [delay] 立刻抛 CancellationException —— 调用链上的 catch 必须原样上抛，
 * 吞掉会让「用户取消」退化成「后台继续执行」。
 */
internal suspend fun cancellableDelay(ms: Long) {
    if (ms <= 0L) return
    delay(ms)
}
