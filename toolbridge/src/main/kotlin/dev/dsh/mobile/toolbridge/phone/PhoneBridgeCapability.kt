package dev.dsh.mobile.toolbridge.phone

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import dev.dsh.mobile.core.platform.device.CapabilityActions
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException


// ---- T4.5 六类系统能力：直接调系统 API，不走无障碍 ----

/**
 * 能力动作入口。返回 null = 不是能力动作，交回无障碍主通道。
 *
 * 缺权限不抛异常，回 E_PERMISSION_DENIED + needsPermission，模型据此提示用户去授权；
 * 系统 API 抛的异常同样翻成错误包 —— 抛出去会被执行器当可重试错误，副作用可能已经落地。
 */
internal suspend fun PhoneBridge.capabilityBlocking(json: String): PhoneResult? {
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

internal fun PhoneBridge.doContacts(obj: JSONObject, started: Long): PhoneResult {
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

internal fun PhoneBridge.doSmsList(obj: JSONObject, started: Long): PhoneResult {
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

internal fun PhoneBridge.doSmsSend(obj: JSONObject, started: Long): PhoneResult {
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

internal fun PhoneBridge.doDial(obj: JSONObject, started: Long): PhoneResult {
    val number = obj.optString("number", "").trim()
    if (number.isEmpty()) return failResult(PhoneErrorCode.BadArgs, "dial requires number")
    context.startActivity(
        Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    return okEnvelope(started, JSONObject().put("dialed", number))
}

internal fun PhoneBridge.doLocation(started: Long): PhoneResult {
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
