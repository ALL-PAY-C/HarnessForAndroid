package dev.dsh.mobile.pluginhost

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前 [PluginHost]。插件 UI 的消费点要读注册表，但没有宿主参数，
 * 所以走 CompositionLocal；宿主没提供时退回 [PluginHostHolder]
 * （AppContainer 里 set 一次即可）。
 */
val LocalPluginHost = staticCompositionLocalOf<PluginHost?> { null }

/** 进程级兜底：CompositionLocal 没接线时槽位页也不至于白屏。 */
object PluginHostHolder {
    @Volatile
    var instance: PluginHost? = null
}
