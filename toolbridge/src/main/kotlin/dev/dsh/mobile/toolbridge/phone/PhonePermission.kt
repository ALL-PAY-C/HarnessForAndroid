package dev.dsh.mobile.toolbridge.phone

/**
 * T1 权限引导的纯逻辑层：只吃「真实状态快照」，不碰 Android API，
 * 便于单测覆盖「未授权 / 已勾选未连上 / 已授权」三态与引导步骤顺序。
 */
enum class PhoneStepStatus { Granted, Pending, Off }

/** 引导步骤。body 直接是用户在系统设置里要走的那条路径。 */
enum class PhonePermissionStep(val title: String, val body: String, val actionLabel: String) {
    Accessibility(
        title = "无障碍服务",
        body = "设置 → 无障碍 → 已下载的服务 → DSH Mobile 手机控制",
        actionLabel = "去开启",
    ),
    Overlay(
        title = "悬浮窗",
        body = "允许显示在其他应用上层，才能看到实时状态浮层（不影响操控）",
        actionLabel = "去授权",
    ),
}

/**
 * 权限快照。serviceEnabled 来自 Settings.Secure 里是否勾选，
 * serviceBound 来自 AccessibilityService 是否真的连上——两者都成立才能下发动作。
 */
data class PhonePermissionState(
    val serviceEnabled: Boolean = false,
    val serviceBound: Boolean = false,
    val overlayGranted: Boolean = false,
) {
    val canControl: Boolean get() = serviceEnabled && serviceBound

    fun status(step: PhonePermissionStep): PhoneStepStatus = when (step) {
        PhonePermissionStep.Accessibility -> when {
            serviceEnabled && serviceBound -> PhoneStepStatus.Granted
            serviceEnabled -> PhoneStepStatus.Pending
            else -> PhoneStepStatus.Off
        }
        PhonePermissionStep.Overlay ->
            if (overlayGranted) PhoneStepStatus.Granted else PhoneStepStatus.Off
    }

    /** 还没到 Granted 的步骤，顺序即引导顺序（无障碍在前）。 */
    val missing: List<PhonePermissionStep>
        get() = PhonePermissionStep.entries.filter { status(it) != PhoneStepStatus.Granted }

    /** 头部一行话：按真实缺口数报数，别在两步都缺时说「还差一步」。 */
    fun headline(): String = when {
        canControl -> "手机控制可用"
        missing.size == 1 -> "还差一步才能操控"
        else -> "还差 " + missing.size + " 步才能操控"
    }

    /** 缺口提示：每条自带句号，直接拼接，拼不出「。；」这种标点连击。 */
    fun missingHint(): String = missing.joinToString("") { step ->
        when (step) {
            PhonePermissionStep.Accessibility ->
                if (serviceEnabled) {
                    "系统已勾选，服务还没连上：回到设置确认服务开关是打开的。"
                } else {
                    "先去 设置 → 无障碍 开启「DSH Mobile 手机控制」。"
                }
            PhonePermissionStep.Overlay -> "再给悬浮窗授权（只影响实时状态浮层，不影响操控）。"
        }
    }
}
