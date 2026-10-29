package dev.dsh.mobile.feature.settings

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 选图落盘。
 *
 * 以前设置页只把 SAF 给的 `content://` uri 存进 prefs，有两个后果：
 * 1. 授权是「可能被系统回收」的，冷启动 openInputStream 抛 SecurityException → 静默回退纯色 / 🐳；
 * 2. 同一个文件重选一次，uri 一模一样，而 Theme 那边用 uri 当缓存键 → 界面完全不刷新
 *    （用户说的「换图后还是旧图」）。
 *
 * 这里把内容复制进 filesDir/appearance 并返回**每次都不一样的绝对路径**，两个问题一起解决：
 * 路径稳定可读，且重选一定换 key。
 */
internal object AppearanceImages {

    private const val DIR = "appearance"

    /** 复制 [uri] 到私有目录，返回新文件的绝对路径；失败返回 null（调用方可回退到直接存 uri）。 */
    suspend fun import(context: Context, uri: Uri, prefix: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val target = File(dir, "$prefix-${System.currentTimeMillis()}.img")
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) {
                target.delete()
                return@runCatching null
            }
            // 同前缀的旧文件删掉：图标 / 壁纸各留一份，避免 filesDir 随选图次数无限增长。
            dir.listFiles()?.forEach { old ->
                if (old.name.startsWith("$prefix-") && old.absolutePath != target.absolutePath) old.delete()
            }
            target.absolutePath
        }.getOrNull()
    }
}
