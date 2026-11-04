package dev.dsh.mobile.toolbridge.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 动作结果只有两态，UI 直接用颜色区分，不做「部分成功」这种含糊状态。 */
enum class PhoneActionStatus { Ok, Failed }

/**
 * 动作目录。id 必须与 PhoneBridge.exec 认的 action 词一一对应，
 * 页面上的动作列表由这里驱动，不再各处手写一遍。
 *
 * 只带 [id]：显示名与说明留在 UI 层（app 的 PhoneActionLabels.kt），数据层不夹文案。
 */
enum class PhoneAction(val id: String) {
    Tap("tap"),
    Swipe("swipe"),
    Type("type"),
    Key("key"),
    Back("back"),
    Home("home"),
    Recents("recents"),
    Scroll("scroll"),
    Pinch("pinch"),
    Drag("drag"),
    ReadScreen("readscreen"),
    ReadTree("tree"),
    WaitFor("wait_for"),
    CurrentApp("currentapp"),
    Apps("apps"),
    Sequence("sequence"),
    OpenApp("openapp"),
    Screenshot("screenshot"),
    // T4.5：六类系统能力动作。id 与 CapabilityActions.PHONE_ACTIONS 逐字一致。
    Contacts("contacts"),
    SmsList("sms_list"),
    SmsSend("sms_send"),
    Dial("dial"),
    Location("location"),
    AudioRecord("audio_record"),
    ;

    companion object {
        fun of(id: String): PhoneAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一条动作记录，用于「状态可视化」列表。at 是 System.currentTimeMillis()。
 *
 * 不带显示名：记录只存 [action] 这个 id，页面按它查 PhoneAction 再取资源文案。
 */
data class PhoneActionRecord(
    val action: String,
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
        val entry = PhoneActionRecord(action, ok, message, at)
        _records.value = (listOf(entry) + _records.value).take(LIMIT)
        return entry
    }

    fun clear() {
        _records.value = emptyList()
    }
}
