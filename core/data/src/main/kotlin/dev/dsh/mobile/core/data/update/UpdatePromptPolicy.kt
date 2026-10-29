package dev.dsh.mobile.core.data.update

/**
 * 该不该提示更新 —— 纯判断，不碰 Android，便于单测。
 *
 * 清单侧（Updater.check）已经滤过一遍，但 UI 侧仍独立判一次：进对话框的路径
 * 可能来自缓存/重试，判定只此一处才能保证语义一致。
 */
fun shouldPromptUpdate(info: UpdateInfo?, installedVersionCode: Long): Boolean =
    info != null && info.versionCode > installedVersionCode

/**
 * 同一版本只打扰一次：已经为这个（或更高）versionCode 弹过就直接放过。
 * 更高的版本出现时重新提示，所以判定是「<」而不是「!=」。
 */
fun shouldPromptUpdateOnce(
    info: UpdateInfo?,
    installedVersionCode: Long,
    promptedVersionCode: Long,
): Boolean {
    if (!shouldPromptUpdate(info, installedVersionCode)) return false
    val target = info ?: return false
    return promptedVersionCode < target.versionCode
}
