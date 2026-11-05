package dev.dsh.mobile.toolbridge.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.dsh.mobile.core.platform.device.CapabilityActions
import dev.dsh.mobile.core.platform.device.CapabilityTier
import dev.dsh.mobile.core.platform.device.CapabilityTiers
import dev.dsh.mobile.core.platform.device.DeviceCapability
import org.json.JSONArray

/**
 * 能力项 ↔ Android 运行时权限。
 *
 * 设置页（feature:settings）与执行侧（PhoneBridge）都读这张表，权限字符串只写一遍。
 * 短信读/发是两个权限，按动作分开查：只看 READ_SMS 就认为能发短信是错的。
 */
object CapabilityPermissions {

    fun ofAction(action: String): List<String> = when (action) {
        CapabilityActions.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        CapabilityActions.SMS_LIST -> listOf(Manifest.permission.READ_SMS)
        CapabilityActions.SMS_SEND -> listOf(Manifest.permission.SEND_SMS)
        CapabilityActions.DIAL -> listOf(Manifest.permission.CALL_PHONE)
        CapabilityActions.LOCATION ->
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        CapabilityActions.AUDIO_RECORD -> listOf(Manifest.permission.RECORD_AUDIO)
        else -> emptyList()
    }

    /** 一类能力的全部权限（设置页按类展示与申请用；短信是读+发两条）。 */
    fun of(capability: DeviceCapability): List<String> = when (capability) {
        DeviceCapability.Camera -> listOf(Manifest.permission.CAMERA)
        DeviceCapability.Microphone -> listOf(Manifest.permission.RECORD_AUDIO)
        DeviceCapability.Contacts -> listOf(Manifest.permission.READ_CONTACTS)
        DeviceCapability.Sms -> listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        DeviceCapability.Dial -> listOf(Manifest.permission.CALL_PHONE)
        DeviceCapability.Location ->
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

/**
 * 六类系统能力的准入闸门。
 *
 * 只做两件事：查运行时权限、把权限缺口翻成统一错误包（[PhoneErrorCode.PermissionDenied] +
 * needsPermission）。「问不问用户」由 core:protocol 的 ToolPolicy 判——那一层才知道有没有
 * 可点的确认弹窗，执行层自己弹窗会绕开逐调用授权。
 */
class CapabilityGate(
    private val context: Context,
    /** 档位来源：AppContainer 注入 SettingsStore 的原始串，默认空串 = 全 Ask。 */
    private val tiers: () -> String,
) {

    fun tier(capability: DeviceCapability): CapabilityTier = CapabilityTiers.tierOf(tiers(), capability)

    fun permissionsOf(action: String): List<String> = CapabilityPermissions.ofAction(action)

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 该动作要的权限是否全都给了。 */
    fun granted(action: String): Boolean = permissionsOf(action).all { hasPermission(it) }

    /** 一类能力的权限是否全给了（设置页展示用）。 */
    fun granted(capability: DeviceCapability): Boolean =
        CapabilityPermissions.of(capability).all { hasPermission(it) }

    /** 缺权限的统一返回：ok=true 外壳 + 内层错误包，带 needsPermission 与要申请的权限清单。 */
    fun deniedResult(action: String): PhoneResult {
        val capability = CapabilityActions.capabilityOf(action)
        val json = phoneErrorJson(
            PhoneErrorCode.PermissionDenied,
            "缺少「" + (capability?.id ?: action) + "」权限：" + action + " 需要用户授权后重试",
        )
            .put("needsPermission", true)
            .put("action", action)
            .put("capability", capability?.id ?: "")
            .put("permissions", JSONArray(permissionsOf(action)))
        return PhoneResult(true, json.toString())
    }
}
