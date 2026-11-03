package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat

/** 无障碍事件去抖窗口：这段时间内的连续内容事件合并成一次代次自增（12.5 次/秒上限）。 */
private const val GENERATION_DEBOUNCE_MS = 80L

/** 无障碍交互入口；悬浮状态只在用户已授予悬浮窗权限时显示。 */
class PhoneAccessibilityService : AccessibilityService() {

    @Volatile
    private var foregroundPkg: String? = null

    @Volatile
    private var foregroundClass: String? = null

    @Volatile
    private var foregroundSince: Long = 0L

    @Volatile
    private var lastAction: PhoneActionRecord? = null
    private var statusView: TextView? = null

    /** 树代次的唯一实例：事件回调写，读侧（tree / T4.2 的 wait_for）读。 */
    private val generations = PhoneTreeGenerations()

    /** 上一次 bump 代次的 uptime，用来给高频内容事件去抖（见 onAccessibilityEvent 注释）。 */
    private var lastGenerationAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        updateOverlay()
        PhoneBridge.onServiceReady()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 三类事件都要记代次：配置里声明了 content/windows changed，只认 state-changed 的话
        // 「界面变了没」就只能靠在外部轮询整棵树（见 PhoneNode.kt 的 PhoneTreeGenerations）。
        //
        // 代次只用事件本身算，绝不在这里读 rootInActiveWindow / e.source：两者都是跨进程阻塞调用
        // （AccessibilityInteractionClient 会一直等到 5s 超时），而这个回调跑在应用主线程上，
        // 卡住它等于卡住输入分发 —— 实测每敲一个字都会取出事件源，连续输入 5s 无响应触发 ANR。
        // 代价是代次变成「可能多报」的粗信号：等待方只会因此更早返回，不会漏掉真实变化。
        val nowUptime = SystemClock.uptimeMillis()
        val stateChanged = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (stateChanged || nowUptime - lastGenerationAt >= GENERATION_DEBOUNCE_MS) {
            lastGenerationAt = nowUptime
            generations.bump()
        }
        if (stateChanged) {
            val pkg = e.packageName?.toString()
            val switched = pkg != foregroundPkg
            foregroundPkg = pkg
            // 只有真的换台才刷新「从什么时候开始」，否则同一窗口的重复事件会把 sinceMs 一直往后推。
            if (switched) {
                // WINDOW_STATE_CHANGED 的 className 常常是根视图类名（android.widget.FrameLayout），
                // 拿它当 activity 名会误导模型；只收看起来像组件名的值，其余如实留空。
                val cls = e.className?.toString().orEmpty()
                foregroundClass = cls.takeIf {
                    it.isNotEmpty() &&
                        !it.startsWith("android.widget.") &&
                        !it.startsWith("android.view.") &&
                        !it.startsWith("android.app.")
                }
                foregroundSince = System.currentTimeMillis()
            }
            statusView?.text = statusText()
        }
    }

    // ---- T4.1 读侧：前台快照与树代次 ----

    /**
     * 事件缓存没有时回落到活动窗口的包名：服务刚被启用时还没收到 WINDOW_STATE_CHANGED，
     * 这时如实返回空会让模型以为「没有前台应用」。
     */
    fun foregroundPackage(): String? = foregroundPkg ?: rootInActiveWindow?.packageName?.toString()

    fun foregroundActivity(): String? = foregroundClass

    fun foregroundSinceMs(): Long = foregroundSince

    fun currentGeneration(): Long = generations.current()

    /** 等到界面变化（代次越过 sinceGeneration）或超时；超时返回 null，不抛。 */
    suspend fun awaitChange(sinceGeneration: Long, timeoutMs: Long): Long? =
        generations.awaitChange(sinceGeneration, timeoutMs)

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeOverlay()
        instance = null
        PhoneBridge.onServiceLost()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    @SuppressLint("InflateParams")
    private fun updateOverlay() {
        removeOverlay()
        if (!Settings.canDrawOverlays(this)) return
        val view = TextView(this).apply {
            text = statusText()
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC202124.toInt())
            setPadding(24, 12, 24, 12)
            isClickable = false
            isFocusable = false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 24 }
        runCatching {
            getSystemService(WindowManager::class.java).addView(view, params)
            statusView = view
        }
    }

    /** PhoneBridge 每完成一次动作回调进来，浮层立刻显示 ✓/✗ + 动作名（状态可视化）。 */
    fun onActionRecorded(record: PhoneActionRecord) {
        lastAction = record
        statusView?.post { statusView?.text = statusText() }
    }

    /** 用户刚在系统里授权悬浮窗后回来，重挂一次浮层（否则要等重启服务）。 */
    fun refreshOverlay() = updateOverlay()

    private fun statusText(): String = buildString {
        append("手机控制已就绪 · ").append(foregroundPkg ?: "等待前台应用")
        lastAction?.let { append(" · ").append(if (it.ok) "✓" else "✗").append(it.label) }
    }

    private fun removeOverlay() {
        statusView?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        statusView = null
    }

    companion object {
        @Volatile
        private var instance: PhoneAccessibilityService? = null

        fun current(): PhoneAccessibilityService? = instance
        fun isRunning(): Boolean = instance != null
    }
}
