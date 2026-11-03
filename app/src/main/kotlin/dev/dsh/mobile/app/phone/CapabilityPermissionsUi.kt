package dev.dsh.mobile.app.phone

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.dsh.mobile.core.common.device.DeviceCapability
import dev.dsh.mobile.toolbridge.phone.CapabilityPermissions

/**
 * 六类系统能力的「查权限 + 弹运行时权限框」（T4.5）。
 *
 * 只能放在 app 模块：feature:settings 没有 activity-compose，弹窗发起不了。
 * 每次弹窗回来、以及从系统设置切回来都会重算一遍，页面上的「已授权」永远是真实值。
 */
class CapabilityPermissionsUi(
    private val context: Context,
    private val request: (Array<String>) -> Unit,
) {
    fun granted(capability: DeviceCapability): Boolean = CapabilityPermissions.of(capability).all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 只申请还没给的那几条；全给了就什么都不做（不会闪一下系统框）。 */
    fun requestMissing(capability: DeviceCapability) {
        val missing = CapabilityPermissions.of(capability)
            .filterNot { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (missing.isNotEmpty()) request(missing)
    }
}

@Composable
fun rememberCapabilityPermissionsUi(): CapabilityPermissionsUi {
    val context = LocalContext.current
    // 每次结果回来 / 每次回前台都 +1，逼下面重新算一遍 granted。
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
    }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    return remember(context, tick) { CapabilityPermissionsUi(context) { permissions -> launcher.launch(permissions) } }
}
