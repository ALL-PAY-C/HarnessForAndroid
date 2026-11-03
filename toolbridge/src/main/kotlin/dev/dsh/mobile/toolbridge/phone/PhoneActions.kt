package dev.dsh.mobile.toolbridge.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 动作结果只有两态，UI 直接用颜色区分，不做「部分成功」这种含糊状态。 */
enum class PhoneActionStatus { Ok, Failed }

/**
 * 动作目录。id 必须与 PhoneBridge.exec 认的 action 词一一对应，
 * 页面上的动作列表由这里驱动，不再各处手写一遍。
 */
enum class PhoneAction(val id: String, val label: String, val hint: String) {
    Tap("tap", "点击", "按坐标或选择器点一下屏幕"),
    Swipe("swipe", "滑动", "两点之间滑动"),
    Type("type", "输入", "往当前焦点输入文本"),
    Key("key", "按键", "系统键或输入框键（back/home/enter…）"),
    Back("back", "返回", "全局返回"),
    Home("home", "主页", "回到桌面"),
    Recents("recents", "最近任务", "打开任务列表"),
    Scroll("scroll", "滚动", "滚可滚容器直到内容真的动"),
    Pinch("pinch", "缩放", "以某点为中心双指缩放"),
    Drag("drag", "拖拽", "从起点拖到终点（可按住）"),
    ReadScreen("readscreen", "读屏", "导出当前界面节点（纯文本）"),
    ReadTree("tree", "读屏树", "导出带 viewId/坐标的结构化节点树"),
    WaitFor("wait_for", "等待条件", "等节点/文本/前台应用出现"),
    CurrentApp("currentapp", "当前应用", "读前台包名与 activity"),
    Apps("apps", "应用列表", "列出可启动的已装应用"),
    Sequence("sequence", "批量步骤", "一次下发多步，失败即停"),
    OpenApp("openapp", "打开应用", "按包名启动应用"),
    Screenshot("screenshot", "截图", "截当前屏幕到文件"),
    // T4.5：六类系统能力动作。id 与 CapabilityActions.PHONE_ACTIONS 逐字一致。
    Contacts("contacts", "联系人", "按 query 查联系人姓名与号码"),
    SmsList("sms_list", "读短信", "读最近收到的短信"),
    SmsSend("sms_send", "发短信", "按号码发一条短信"),
    Dial("dial", "拨号", "按号码直接拨出（要 CALL_PHONE）"),
    Location("location", "定位", "读最近一次已知位置"),
    AudioRecord("audio_record", "录音", "录一段音频到应用缓存目录"),
    ;

    companion object {
        fun of(id: String): PhoneAction? = entries.firstOrNull { it.id == id }
    }
}

/** 一条动作记录，用于「状态可视化」列表。at 是 System.currentTimeMillis()。 */
data class PhoneActionRecord(
    val action: String,
    val label: String,
    val ok: Boolean,
    val message: String,
    val at: Long,
) {
    fun status(): PhoneActionStatus = if (ok) PhoneActionStatus.Ok else PhoneActionStatus.Failed
}

/**
 * 进程内动作日志。PhoneBridge 每次下发后写一条，页面与悬浮窗都观察它。
 * ponytail: 只保留最近 [LIMIT] 条，历史留档走 .mock 日志；要更长历史就换 Room。
 */
object PhoneActionLog {
    const val LIMIT = 20

    private val _records = MutableStateFlow<List<PhoneActionRecord>>(emptyList())
    val records: StateFlow<List<PhoneActionRecord>> = _records

    fun record(action: String, ok: Boolean, message: String, at: Long = System.currentTimeMillis()): PhoneActionRecord {
        val label = PhoneAction.of(action)?.label ?: action
        val entry = PhoneActionRecord(action, label, ok, message, at)
        _records.value = (listOf(entry) + _records.value).take(LIMIT)
        return entry
    }

    fun clear() {
        _records.value = emptyList()
    }
}
