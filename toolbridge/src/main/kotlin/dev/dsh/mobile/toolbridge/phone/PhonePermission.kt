package dev.dsh.mobile.toolbridge.phone

/**
 * T1 权限引导的纯逻辑层：只吃「真实状态快照」，不碰 Android API，
 * 便于单测覆盖「未授权 / 已勾选未连上 / 已授权」三态与引导步骤顺序。
 *
 * T9.19：这里只留结构（步骤 / 结论 / 缺口），标题与引导语由 UI 层按资源渲染，
 * 数据层不再出现面向用户的中文字面量。
 */
enum class PhoneStepStatus { Granted, Pending, Off }

/** 引导步骤。顺序即引导顺序（无障碍在前）；文案在 UI 侧按资源取。 */
enum class PhonePermissionStep { Accessibility, Overlay }

/** 头部结论：可用 / 还差一步 / 还差 N 步。 */
sealed interface PhoneHeadline {
    data object Ready : PhoneHeadline
    data object OneStepLeft : PhoneHeadline
    data class StepsLeft(val count: Int) : PhoneHeadline
}

/** 缺口提示：每条自带句号，UI 侧按顺序拼接，拼不出「。；」这种标点连击。 */
enum class PhoneHint { ServiceNotConnected, EnableAccessibility, GrantOverlay }

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
    fun headline(): PhoneHeadline = when {
        canControl -> PhoneHeadline.Ready
        missing.size == 1 -> PhoneHeadline.OneStepLeft
        else -> PhoneHeadline.StepsLeft(missing.size)
    }

    /** 缺口提示：顺序与 [missing] 一致。 */
    fun missingHint(): List<PhoneHint> = missing.map { step ->
        when (step) {
            PhonePermissionStep.Accessibility ->
                if (serviceEnabled) PhoneHint.ServiceNotConnected else PhoneHint.EnableAccessibility
            PhonePermissionStep.Overlay -> PhoneHint.GrantOverlay
        }
    }
}
