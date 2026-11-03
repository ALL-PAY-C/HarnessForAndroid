package dev.dsh.mobile.toolbridge.phone

import org.json.JSONObject

/**
 * 错误码。码名照抄 T40 §3.7 的 16 个码表，不自创命名体系；只落实际用到的那些。
 *
 * [retryable] 只描述错误码本身（再试一次有没有意义）；副作用是否真落地由调用方的
 * effectLanded 单独判断，不能靠 retryable 推。
 */
enum class PhoneErrorCode(val code: String, val retryable: Boolean) {
    A11yOff("E_A11Y_OFF", retryable = false),
    BadArgs("E_BAD_ARGS", retryable = false),
    NodeNotFound("E_NODE_NOT_FOUND", retryable = true),
    NodeAmbiguous("E_NODE_AMBIGUOUS", retryable = false),
    NodeNotActionable("E_NODE_NOT_ACTIONABLE", retryable = false),
    StaleSnapshot("E_STALE_SNAPSHOT", retryable = true),
    SnapshotDrift("E_SNAPSHOT_DRIFT", retryable = false),
    Timeout("E_TIMEOUT", retryable = true),
    /** wait_for 超时：独立码，不复用 E_TIMEOUT（后者是「无障碍线程没返回」，重试语义不同）。 */
    WaitTimeout("E_WAIT_TIMEOUT", retryable = true),
    ActionRejected("E_ACTION_REJECTED", retryable = false),
    GestureRejected("E_GESTURE_REJECTED", retryable = false),
    NoFocusedEditable("E_NO_FOCUSED_EDITABLE", retryable = true),
    NodeNoImeAction("E_NODE_NO_IME_ACTION", retryable = false),
    ArtifactWrite("E_ARTIFACT_WRITE", retryable = false),
    ;

    companion object {
        fun of(code: String): PhoneErrorCode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 统一错误包：{ok:false, code, message, retryable}，动作侧再带 effectLanded。
 * effectLanded 缺省就不写这个字段 —— 读侧的既有错误包保持四字段，不多塞东西。
 */
internal fun phoneErrorJson(code: PhoneErrorCode, message: String, effectLanded: Boolean? = null): JSONObject {
    val json = JSONObject()
        .put("ok", false)
        .put("code", code.code)
        .put("message", message)
        .put("retryable", code.retryable)
    if (effectLanded != null) json.put("effectLanded", effectLanded)
    return json
}
