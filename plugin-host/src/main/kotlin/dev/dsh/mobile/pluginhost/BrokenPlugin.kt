package dev.dsh.mobile.pluginhost

import dev.dsh.mobile.pluginapi.DshPlugin
import dev.dsh.mobile.pluginapi.PluginManifest
import dev.dsh.mobile.pluginapi.PluginRegistry
import java.io.File

/**
 * 装配期就注定跑不起来的插件的占位实现（T9.22）。
 *
 * 以前三种形态失败后只写 AppLog.w 就跳过：APK/dex 装载失败、插件目录已丢失、
 * assets/plugin.json 读不出来。跳过意味着插件管理列表里连一行都没有，用户以为
 * 「装没装上都不知道」；或者更糟 —— 行还在，按启用意图显示「已启用」，而它其实
 * 从来没跑起来。
 *
 * 占位插件正常登记进列表，[register] 空转（不贡献任何槽位，零副作用），
 * 失败原因通过 [PluginLoadFailure] 交给 [PluginHost.load] 落进
 * [LoadedPlugin.loadError]，行内才看得见真相。
 */
class BrokenPlugin(
    override val manifest: PluginManifest,
    /** 断在哪一步：apk（代码装载）/ manifest（清单解析）/ missing（目录丢失）。 */
    val stage: String,
    /** 中文一行原因，进列表行与插件页；[loadFailure] = "[" + stage + "] " + reason。 */
    val reason: String,
) : DshPlugin, PluginLoadFailure {

    override val loadFailure: String get() = "[" + stage + "] " + reason

    /** 空转：占位插件没有可执行内容，注册槽位只会再造一个假界面。 */
    override fun register(registry: PluginRegistry) = Unit

    companion object {

        /** APK/dex 形态装载失败：类找不到、dex 不可读、构造函数抛错都归这一类。 */
        fun apk(id: String, name: String, version: String, message: String): BrokenPlugin =
            BrokenPlugin(
                manifest = manifestOf(id, name, version, "加载失败：" + message),
                stage = STAGE_APK,
                reason = "加载失败：" + message,
            )

        /** 清单已登记、目录却没了：没有 payload 可执行，只能报出路径让人去找回。 */
        fun missingDir(id: String, name: String, version: String, dir: File): BrokenPlugin {
            val reason = "插件目录已不存在：" + dir.absolutePath
            return BrokenPlugin(
                manifest = manifestOf(id, name, version, reason),
                stage = STAGE_MISSING,
                reason = reason,
            )
        }

        fun missingDir(spec: PluginManifestFile, dir: File): BrokenPlugin =
            missingDir(spec.id, spec.name, spec.version, dir)

        /** declarative/HTML 形态：assets/plugin.json 有条目但读不出来。 */
        fun badManifest(id: String, name: String, version: String, message: String): BrokenPlugin {
            val reason = "assets/plugin.json 解析失败：" + message
            return BrokenPlugin(
                manifest = manifestOf(id, name, version, reason),
                stage = STAGE_MANIFEST,
                reason = reason,
            )
        }

        private fun manifestOf(id: String, name: String, version: String, description: String) =
            PluginManifest(
                id = id,
                name = name.ifBlank { id },
                version = version.ifBlank { "0" },
                description = description,
                capabilities = emptySet(),
                enabledByDefault = true,
            )

        const val STAGE_APK: String = "apk"
        const val STAGE_MANIFEST: String = "manifest"
        const val STAGE_MISSING: String = "missing"
    }
}

/**
 * 装配失败 → 占位插件。抽成纯函数，装配路径与测试共用同一套判定：
 * 清单条目在但解析失败算 [BrokenPlugin.STAGE_MANIFEST]，其余算 [BrokenPlugin.STAGE_APK]。
 *
 * [manifestError] 由调用方探测（assets/plugin.json 存在却读不出来才有值）。
 */
internal fun brokenPluginFor(
    id: String,
    name: String,
    version: String,
    manifestError: String?,
    error: Throwable?,
): BrokenPlugin {
    val message = error?.message?.takeIf { it.isNotBlank() }
        ?: error?.toString()
        ?: "未知错误"
    return if (manifestError != null) {
        BrokenPlugin.badManifest(id, name, version, manifestError)
    } else {
        BrokenPlugin.apk(id, name, version, message)
    }
}
