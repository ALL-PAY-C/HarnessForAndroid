package dev.dsh.mobile.plugin.html

import dev.dsh.mobile.core.common.AppLog

/**
 * [RenderScript] 已直接读 classpath 里的 render.js；这里保留一层薄封装，
 * 给「想热替换脚本来调样式」留一个入口（外部放文件即生效，不需要重编插件）。
 */
internal object RenderAssets {

    fun js(context: android.content.Context): String {
        val override = overrideFile(context)
        if (override != null) {
            val text = runCatching { override.readText() }
                .onFailure { AppLog.w("dsh.html: 读 " + override.name + " 失败，回退内置 render.js", it) }
                .getOrNull()
            if (!text.isNullOrBlank() && text.contains("DSH_RENDER")) {
                AppLog.i("dsh.html: 使用外部 render.js " + override.name + " (" + text.length + " chars)")
                return text
            }
        }
        return RenderScript.JS
    }

    private fun overrideFile(context: android.content.Context): java.io.File? {
        val f = java.io.File(java.io.File(context.filesDir, DIR), NAME)
        return if (f.isFile) f else null
    }

    private const val DIR = "html-render"
    private const val NAME = "render.js"
}