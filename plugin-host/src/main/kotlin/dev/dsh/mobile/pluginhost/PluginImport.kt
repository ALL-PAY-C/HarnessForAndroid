package dev.dsh.mobile.pluginhost

import java.io.File

/** 「从文件安装」选中物的形态。 */
enum class ImportKind {
    /** 旧的整包分发：zip 根上有 AndroidManifest.xml 或 classes*.dex。 */
    Apk,

    /** 数据插件包：zip（package.json + 入口，或 plugin.json）或 npm tarball。 */
    PluginPackage,

    /** 两样都不是 —— 用户挑错了文件，连解包都不用试。 */
    Garbage,
}

/**
 * 按内容判型 —— 后缀不可信，SAF 常把文件名吃掉。
 *
 * 只读文件头 4 字节判容器，再让 [PluginPackageFormat.isAndroidApk] 决定是 APK 还是数据包，
 * 所以能在 JVM 单测里直接跑（不碰 Context）。
 */
object PluginImport {

    /** 判成 [ImportKind.Garbage] 时给用户看的原因（后面接文件名）。 */
    const val GARBAGE_MESSAGE: String = "所选文件不是插件包（既不是 zip 插件包也不是 APK）："

    fun classify(file: File): ImportKind {
        if (!file.isFile) return ImportKind.Garbage
        val head = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(4)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }.getOrDefault(ByteArray(0))
        return when {
            isZip(head) -> if (PluginPackageFormat.isAndroidApk(file)) ImportKind.Apk else ImportKind.PluginPackage
            isGzip(head) -> ImportKind.PluginPackage
            else -> ImportKind.Garbage
        }
    }

    /** PK\x03\x04（或不带数据描述的 PK\x05\x06 空包）。 */
    private fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte() &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte())

    /** 1f 8b —— npm tarball / .tar.gz。 */
    private fun isGzip(head: ByteArray): Boolean =
        head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
}
