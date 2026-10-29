package dev.dsh.mobile.linux

import android.os.Build

/**
 * Android 的 ABI 名字和 rootfs 制品里的名字不是一回事：
 * Build.SUPPORTED_ABIS 给 "x86_64"/"arm64-v8a"，制品清单也用这两个值，
 * 但 armeabi-v7a、riscv64 之类我们没有制品，必须明确判成"不支持"而不是拿错包。
 */
object LinuxAbis {
    const val X86_64 = "x86_64"
    const val ARM64 = "arm64-v8a"

    /** 纯函数，方便单测；取第一个有制品的 ABI。 */
    fun map(abis: List<String>): String? = abis.firstOrNull { it == X86_64 || it == ARM64 }

    fun current(): String? = map(Build.SUPPORTED_ABIS?.toList().orEmpty())
}
