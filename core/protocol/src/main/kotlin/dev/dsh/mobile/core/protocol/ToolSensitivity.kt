package dev.dsh.mobile.core.protocol

/**
 * 工具敏感度分档（T6.1b）。
 *
 * 分档只描述「这个动作会不会动到用户的真实数据或别人的应用」，不决定拦不拦 ——
 * 拦不拦由 [ToolPolicy.verdict] 按档位 + 全局自动批准设置决定，两件事分开才好审计。
 */
enum class ToolSensitivity {
    /** 只读：读文件、看树、查后台状态、web 检索。 */
    ReadOnly,

    /** 可回滚写：写文件、建目录、装技能、改待办。 */
    ReversibleWrite,

    /** 不可逆：删文件、跑子代理、多步序列（一步错后面全错）。 */
    Irreversible,

    /** 操控设备：点击/输入/缩放，会影响别的 App 的界面状态。 */
    DeviceControl,
}
