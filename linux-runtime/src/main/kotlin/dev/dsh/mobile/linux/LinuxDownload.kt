package dev.dsh.mobile.linux

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * rootfs 下载器：只用 JDK 自带 HttpURLConnection，不引第三方 HTTP 栈。
 * 只负责把字节落到 dest；校验（字节数 + sha256）由 LinuxEnvironment 做，
 * 免得下载器自己"看起来成功"就把坏包激活了。
 */
object LinuxDownload {

    private const val TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    fun http(manifest: LinuxManifest, dest: File) {
        var url = manifest.url
        var hop = 0
        while (true) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "dsh-mobile-linux-runtime")
            }
            try {
                val code = conn.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val next = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("redirect without Location: $url")
                    if (++hop > MAX_REDIRECTS) throw IllegalStateException("too many redirects: $url")
                    url = URL(URL(url), next).toString()
                    continue
                }
                if (code != 200) throw IllegalStateException("http $code for $url")
                dest.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }
}
