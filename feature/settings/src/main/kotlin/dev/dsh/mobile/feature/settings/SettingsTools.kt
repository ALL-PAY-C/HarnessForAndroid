package dev.dsh.mobile.feature.settings

import dev.dsh.mobile.core.platform.ProxyEnv
import dev.dsh.mobile.design.UiText

internal fun downloadLimitBytes(text: String): Long? =
    text.trim().toLongOrNull()?.takeIf { it in 1L..4096L }?.times(1024L * 1024L)

fun linuxInstallationLabel(version: String?, abi: String?): String? =
    if (version.isNullOrBlank() || abi.isNullOrBlank()) null else "$version / $abi"

/**
 * 「所有文件访问权限」设置页的 action。写成字面量而不是引用 android.provider.Settings：
 * 这两个函数是纯 JVM 的，才好在单测里直接断言。
 */
private const val MANAGE_ALL_FILES_ACTION = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"

/** Android 11 起才有这个设置页；null = 本机没有，调用方据此隐藏条目（也免得 startActivity 抛）。 */
fun fullFileAccessAction(sdkInt: Int): String? = if (sdkInt >= 30) MANAGE_ALL_FILES_ACTION else null

// 状态文案在 SettingsScreen.kt 里由 R.string.settings_ffa_granted / settings_ffa_denied 决定；
// 真实可用性由 AppStorage/FileBridge 判（同一份真相），helper 只负责这个判断。

/**
 * 代理输入的合法性：空 = 直连，合法；非空必须能被 ProxyEnv 解析成「scheme + host:port」。
 * true 表示不可保存，错误文案在 UI 侧取 R.string.settings_proxy_invalid。
 */
fun proxyInvalid(raw: String): Boolean = raw.isNotBlank() && ProxyEnv.normalize(raw).isEmpty()

/** 设置页副标题：null = 直连（叫法由 UI 侧的 settings_direct 决定），别让用户以为代理没存上。 */
fun proxyLabel(raw: String): String? = if (raw.isBlank()) null else raw.trim()

data class LinuxSettingsState(
    /** null = 未安装；安装信息由 linux 工具链给，界面按 null 换成本地化文案。 */
    val rootfs: String? = null,
    val kernel: String? = null,
    /** null = 尚未自检。 */
    val health: String? = null,
    val usageBytes: Long = 0,
    val checks: List<String> = emptyList(),
    val busy: Boolean = false,
    val progress: UiText? = null,
    val error: String? = null,
    val message: String? = null,
    val diagnostics: String = "",
)

interface LinuxSettingsActions {
    suspend fun cleanup(): LinuxSettingsState
    suspend fun clearDiagnostics(): LinuxSettingsState
    suspend fun inspect(): LinuxSettingsState
    suspend fun installEnvironment(progress: (String) -> Unit): LinuxSettingsState
    suspend fun repairRuntime(): LinuxSettingsState
    suspend fun reinstallRuntime(progress: (String) -> Unit): LinuxSettingsState
    suspend fun installKernel(progress: (String) -> Unit): LinuxSettingsState
    suspend fun selfCheck(progress: (String) -> Unit): LinuxSettingsState

    /** 一键补齐 Termux / proot / pip / git 镜像，跑完回填 check 行与耗时汇总。 */
    suspend fun bootstrapEnvironment(progress: (String) -> Unit): LinuxSettingsState
}
