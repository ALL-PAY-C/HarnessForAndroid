package dev.dsh.mobile.feature.settings

import dev.dsh.mobile.core.common.ProxyEnv

internal fun downloadLimitBytes(text: String): Long? =
    text.trim().toLongOrNull()?.takeIf { it in 1L..4096L }?.times(1024L * 1024L)

fun linuxInstallationLabel(version: String?, abi: String?): String =
    if (version.isNullOrBlank() || abi.isNullOrBlank()) "未安装" else "$version / $abi"

fun missingLinuxManifest(abi: String): String = "缺少 Linux 制品清单：$abi"

/**
 * 「所有文件访问权限」设置页的 action。写成字面量而不是引用 android.provider.Settings：
 * 这两个函数是纯 JVM 的，才好在单测里直接断言。
 */
private const val MANAGE_ALL_FILES_ACTION = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"

/** Android 11 起才有这个设置页；null = 本机没有，调用方据此隐藏条目（也免得 startActivity 抛）。 */
fun fullFileAccessAction(sdkInt: Int): String? = if (sdkInt >= 30) MANAGE_ALL_FILES_ACTION else null

/** 状态文案。真实可用性由 AppStorage/FileBridge 判（同一份真相），这里只负责措辞。 */
fun fullFileAccessLabel(hasAccess: Boolean): String =
    if (hasAccess) "已授权 · 文件工具可读写整个内部存储"
    else "未授权 · 文件工具只能读写应用私有目录，点击去授权"

/**
 * 代理输入的合法性：空 = 直连，合法；非空必须能被 ProxyEnv 解析成「scheme + host:port」。
 * 返回 null 表示可保存，否则是给用户看的错误文案。
 */
fun proxyError(raw: String): String? =
    if (raw.isBlank() || ProxyEnv.normalize(raw).isNotEmpty()) null
    else "代理地址无效，示例：http://127.0.0.1:7890 或 socks5://127.0.0.1:1080"

/** 设置页副标题：空值就是直连，别让用户以为代理没存上。 */
fun proxyLabel(raw: String): String = if (raw.isBlank()) "直连" else raw.trim()

data class LinuxSettingsState(
    val rootfs: String = "未安装",
    val kernel: String = "未安装",
    val health: String = "尚未自检",
    val usageBytes: Long = 0,
    val checks: List<String> = emptyList(),
    val busy: Boolean = false,
    val progress: String? = null,
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
