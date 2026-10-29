package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T3 单元侧：只有 x86_64 / arm64-v8a 有制品，其它 ABI 必须判成不支持。 */
class LinuxAbisTest {

    @Test
    fun `x86_64 与 arm64-v8a 被识别`() {
        assertEquals(LinuxAbis.X86_64, LinuxAbis.map(listOf("x86_64")))
        assertEquals(LinuxAbis.ARM64, LinuxAbis.map(listOf("arm64-v8a")))
    }

    @Test
    fun `多 ABI 设备取第一个有制品的`() {
        assertEquals(LinuxAbis.ARM64, LinuxAbis.map(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(LinuxAbis.X86_64, LinuxAbis.map(listOf("x86_64", "x86")))
    }

    @Test
    fun `没有制品的 ABI 返回 null 而不是猜一个`() {
        assertNull(LinuxAbis.map(listOf("armeabi-v7a", "armeabi")))
        assertNull(LinuxAbis.map(emptyList()))
    }
}
