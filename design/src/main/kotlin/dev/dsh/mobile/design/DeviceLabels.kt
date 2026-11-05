package dev.dsh.mobile.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.core.platform.device.CapabilityTier
import dev.dsh.mobile.core.platform.device.DeviceCapability

/**
 * 设备能力词表的显示层映射。
 *
 * core:platform 的枚举只带 id（数据层不夹文案），文案在这里按项查 design 自己的资源，
 * 切系统语言后重新渲染即生效，JVM 单测也不需要 Context。
 * 加一类能力：枚举加一项 + catalog.json 加两条 + 本文件加两个分支（when 是穷尽的，漏了编译不过）。
 */
@Composable
fun DeviceCapability.title(): String = stringResource(
    when (this) {
        DeviceCapability.Camera -> R.string.cap_camera_title
        DeviceCapability.Microphone -> R.string.cap_microphone_title
        DeviceCapability.Contacts -> R.string.cap_contacts_title
        DeviceCapability.Sms -> R.string.cap_sms_title
        DeviceCapability.Dial -> R.string.cap_dial_title
        DeviceCapability.Location -> R.string.cap_location_title
    },
)

@Composable
fun DeviceCapability.hint(): String = stringResource(
    when (this) {
        DeviceCapability.Camera -> R.string.cap_camera_hint
        DeviceCapability.Microphone -> R.string.cap_microphone_hint
        DeviceCapability.Contacts -> R.string.cap_contacts_hint
        DeviceCapability.Sms -> R.string.cap_sms_hint
        DeviceCapability.Dial -> R.string.cap_dial_hint
        DeviceCapability.Location -> R.string.cap_location_hint
    },
)

/** 授权三档的标签：始终允许 / 本会话 / 每次询问。 */
@Composable
fun CapabilityTier.label(): String = stringResource(
    when (this) {
        CapabilityTier.Always -> R.string.cap_tier_always
        CapabilityTier.Session -> R.string.cap_tier_session
        CapabilityTier.Ask -> R.string.cap_tier_ask
    },
)
