package dev.dsh.mobile.app.diag

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.diag.SafeMode

/**
 * 安全模式当前该禁什么（T9.12 §3.4）。三个开关一次给全：
 * 调用方只读自己关心的那一个，不各自去猜别的标志位。
 */
data class SafeModeFlags(
    /** 只放行 dsh.* 内置插件。 */
    val disablePlugins: Boolean,
    /** 不自动 connectActive / 不自启 RuntimeService；UI 仍保留手动启动。 */
    val disableAutoEngine: Boolean,
    /** 手机自动化工具降为 ASK_ONLY。 */
    val disableAccessibility: Boolean,
)

/**
 * 安全模式的持久标志（T9.12 §3.4）。
 *
 * 状态落在 BootGuardStore 的 boot.guard.* 前缀里，不新建 SharedPreferences 文件：
 * 安全模式本来就是「启动账本」的一个字段，拆成两份存储只会让「计数归零」和
 * 「安全模式粘住」这两件事分叉。
 *
 * 粘性：enter 之后即使后续启动看起来正常也不自动解除，只能由用户显式 exit()。
 * 否则崩溃循环会在「进安全模式 → 下次看起来正常 → 自动退出 → 又崩」之间抖动。
 */
class SafeModeController(
    private val store: BootGuardStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 计数器归零/退出安全模式复用 core:model 的既有语义，不在这里重写一套键名。 */
    private val boot = SafeMode(store, clock)

    fun isActive(): Boolean = store.get(FLAG) == "1" || boot.isSafeMode()

    /** 进入原因；未进入安全模式时返回 null。老版本存的是内部枚举名，读的时候翻成人话。 */
    fun reason(): String? = if (isActive()) store.get(REASON)?.let(::localized) else null

    private fun localized(raw: String): String = when (raw) {
        "repeated-boot-failures" -> "连续启动失败"
        "OOM" -> "上次启动崩溃（内存不足）"
        "PLUGIN" -> "上次启动崩溃（插件）"
        "ENGINE" -> "上次启动崩溃（引擎）"
        "DATABASE" -> "上次启动崩溃（数据库）"
        "UI" -> "上次启动崩溃（界面）"
        else -> raw
    }

    /**
     * 当前生效的标志。reason 为 null（没进安全模式）时全部为 false ——
     * 判定只有「在安全模式」和「不在」两种，不存在半开状态。
     */
    fun flags(reason: String? = reason()): SafeModeFlags {
        val on = reason != null
        return SafeModeFlags(disablePlugins = on, disableAutoEngine = on, disableAccessibility = on)
    }

    fun enter(reason: String) {
        store.put(FLAG, "1")
        store.put(REASON, reason)
        store.put(AT, clock().toString())
        AppLog.w("SAFE_MODE_STATE enter reason=" + reason)
    }

    /**
     * 用户显式退出：清标志、清计数、保留崩溃文件与快照。
     * 计数归零走 SafeMode.markBootOk()，下次启动的 beginBoot 从 1 开始。
     */
    fun exit() {
        store.put(FLAG, null)
        store.put(REASON, null)
        store.put(AT, null)
        boot.exitSafeMode()
        boot.markBootOk()
        AppLog.i("SAFE_MODE_STATE exit")
    }

    private companion object {
        const val FLAG = "boot.guard.safeMode"
        const val REASON = "boot.guard.safeMode.reason"
        const val AT = "boot.guard.safeMode.at"
    }
}
