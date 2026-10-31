package dev.dsh.mobile.design

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 界面级偏好：UI 修改模式 / 正文·代码·终端三套字号。
 *
 * 为什么不用 core:data 的 DataStore：那层的 AppSettings 是「连哪个引擎、用哪个模型」
 * 这类运行配置，界面排版不属于它；再往 core 加字段会把排版语义漏进领域模型。
 * 这里跟 plugin-host 的 SlotPlacementStore 一样走 SharedPreferences，attach 一次即可。
 *
 * ponytail: 三个字号都是倍率（1f = 现状），没有做「px 级」精调，界面上一档 0.05。
 */
object UiLayoutPrefs {
    private const val FILE = "dsh.ui.prefs"
    private var sp: SharedPreferences? = null

    var uiEditEnabled: Boolean by mutableStateOf(false)
        private set
    var fontBody: Float by mutableStateOf(1f)
        private set
    var fontCode: Float by mutableStateOf(1f)
        private set
    var fontTerminal: Float by mutableStateOf(1f)
        private set

    /** AppNav 启动时调一次；重复调用无副作用。 */
    fun attach(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        uiEditEnabled = p.getBoolean("uiEdit", false)
        fontBody = p.getFloat("fontBody", 1f)
        fontCode = p.getFloat("fontCode", 1f)
        fontTerminal = p.getFloat("fontTerminal", 1f)
        // 落盘的开关直接写进全局编辑态，首帧就一致。
        UiEditMode.setEditEnabled(uiEditEnabled)
    }

    fun enableUiEdit(on: Boolean) {
        uiEditEnabled = on
        UiEditMode.setEditEnabled(on)
        write { putBoolean("uiEdit", on) }
    }

    fun setBodyFont(v: Float) {
        fontBody = v
        write { putFloat("fontBody", v) }
    }

    fun setCodeFont(v: Float) {
        fontCode = v
        write { putFloat("fontCode", v) }
    }

    fun setTerminalFont(v: Float) {
        fontTerminal = v
        write { putFloat("fontTerminal", v) }
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        val editor = sp?.edit() ?: return
        editor.block()
        editor.apply()
    }
}
