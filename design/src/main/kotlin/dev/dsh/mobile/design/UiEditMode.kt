package dev.dsh.mobile.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「UI 修改模式」全局开关。
 *
 * 与 [SlotEditState] 分开：那个管插件组件的摆放，这个管界面布局本身能不能编辑。
 * 两者可以各开各的。关闭状态下任何位置都不显示「+」，界面与从前完全一致。
 *
 * 状态机只有三件事：默认关、可开可关、以及唯一的判定入口 [plusVisible]。
 * 界面各处不自己判 enabled，一律走 plusVisible(slot)，这样以后想收紧成
 * 「只开放部分位置」时改一处即可。
 */
object UiEditMode {
    /** 界面上可编辑的位置。顺序即界面里展示的顺序。 */
    enum class Slot(val label: String) {
        SessionList("会话列表项"),
        ChatTopBar("会话顶栏"),
        Composer("输入框区"),
        BottomBar("底部 tab 栏"),
        DrawerGroup("抽屉分组"),
    }

    /** 默认关闭：装完即用的界面不该一上手就能被改。 */
    var enabled: Boolean by mutableStateOf(false)
        private set

    /** 开关入口。[UiLayoutPrefs] 负责落盘，这里只留内存态。 */
    fun setEditEnabled(on: Boolean) {
        enabled = on
    }

    /** 这个位置此刻是否显示「+」。 */
    fun plusVisible(slot: Slot): Boolean = enabled

    val slots: List<Slot> get() = Slot.values().toList()
}
