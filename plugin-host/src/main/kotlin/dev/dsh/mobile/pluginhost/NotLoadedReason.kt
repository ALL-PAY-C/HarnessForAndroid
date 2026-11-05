package dev.dsh.mobile.pluginhost

/**
 * 「这一行现在没跑，是谁决定的」。
 *
 * 与 [LoadedPlugin.loadError] 严格分工：loadError 只在引擎真的跑过并失败时才有值；
 * 宿主策略挡着（开关关着、安全模式、权限没授全）或引擎根本没起来时，插件同样没跑，
 * 但那是「未加载」不是「加载失败」—— 两者在列表里必须长得不一样，用户才会去做对的动作
 * （开开关 / 退安全模式 / 授权 / 重启引擎），而不是去翻一份并不存在的错误原文。
 */
enum class NotLoadedReason {
    /** 用户把开关关了。 */
    Disabled,

    /** 安全模式暂停了第三方插件（连续启动失败的保护）。 */
    SafeMode,

    /** 清单声明了权限，用户还没授全。 */
    Permission,

    /** Node 引擎启动失败，插件一次都没被 import。 */
    EngineUnavailable,

    /** 该跑但还没跑过（还没扫描到 / 引擎还没开始），既不是策略也不是失败。 */
    NotStarted,
}
