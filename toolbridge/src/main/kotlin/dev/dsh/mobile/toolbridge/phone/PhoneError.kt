package dev.dsh.mobile.toolbridge.phone

import org.json.JSONObject

/**
 * 读侧错误码。码名照抄 T40 §3.7 的 16 个码表，不自创命名体系；
 * 这里只落本期用得到的几个，T4.2 的动作用同一个枚举往下加。
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
    ArtifactWrite("E_ARTIFACT_WRITE", retryable = false),
    ;

    companion object {
        fun of(code: String): PhoneErrorCode? = entries.firstOrNull { it.code == code }
    }
}

/** 统一错误包：{ok:false, code, message, retryable}。 */
internal fun phoneErrorJson(code: PhoneErrorCode, message: String): JSONObject = JSONObject()
    .put("ok", false)
    .put("code", code.code)
    .put("message", message)
    .put("retryable", code.retryable)
