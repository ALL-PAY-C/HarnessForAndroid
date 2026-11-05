package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import dev.dsh.mobile.toolbridge.ShizukuChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

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
    internal val context: Context,
    /**
     * T4.5：六类系统能力的档位来源（SettingsStore 里存的原始串）。由 AppContainer 注入；
     * 默认空串 = 全 Ask，也就是「没配置就按最保守的来」。
     */
    private val capabilityTiers: () -> String = { "" },
) {

    /** 能力动作的准入闸门：只查运行时权限，问不问用户由 core:protocol 的 ToolPolicy 决定。 */
    internal val capabilityGate = CapabilityGate(context, capabilityTiers)

    @Volatile
    internal var recording = false

    internal val a11yExecutor = Executors.newSingleThreadExecutor { r ->
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

    internal suspend fun execBlocking(json: String): PhoneResult {
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

    private fun isTreeAction(json: String): Boolean =
        runCatching { JSONObject(json).optString("action", "") == ACTION_TREE }.getOrDefault(false)

    private fun isV2Action(json: String): Boolean =
        runCatching { JSONObject(json).optString("action", "") in V2_ACTIONS }.getOrDefault(false)

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

