package dev.dsh.mobile.linux

import java.io.File

/** 只认 64 位小端 ELF 的 e_machine，用来判断 .so 到底是不是目标 ABI。 */
object ElfHeader {

    fun machineOf(bytes: ByteArray): String? {
        if (bytes.size < 20) return null
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return null
        if (bytes[4] != 2.toByte()) return null // ELFCLASS64
        val machine = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
        return when (machine) {
            62 -> "x86_64"
            183 -> "aarch64"
            else -> null
        }
    }

    fun machineOf(file: File): String? {
        if (!file.isFile) return null
        val head = ByteArray(20)
        val read = file.inputStream().use { it.read(head) }
        return if (read < 20) null else machineOf(head)
    }
}
