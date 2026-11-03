package dev.dsh.buildlogic

import java.io.File
import java.security.MessageDigest

/** 流式 SHA-256：大文件（>= 100 MB）不整读进内存。 */
object Hashes {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return sha256(file).equals(expectedSha256.trim(), ignoreCase = true)
    }
}
