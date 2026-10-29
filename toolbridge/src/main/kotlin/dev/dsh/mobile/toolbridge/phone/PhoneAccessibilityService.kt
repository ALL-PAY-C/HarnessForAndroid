package dev.dsh.mobile.toolbridge.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat

/** 无障碍交互入口；悬浮状态只在用户已授予悬浮窗权限时显示。 */
class PhoneAccessibilityService : AccessibilityService() {

    @Volatile
    private var foregroundPkg: String? = null

    @Volatile
    private var lastAction: PhoneActionRecord? = null
    private var statusView: TextView? = null

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
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPkg = e.packageName?.toString()
            statusView?.text = statusText()
        }
    }

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
