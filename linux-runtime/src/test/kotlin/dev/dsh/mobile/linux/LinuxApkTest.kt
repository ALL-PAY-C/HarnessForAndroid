package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R2.2：镜像探测、选择、回退。全程不联网——脚本只做形状断言，选择用纯函数打桩。 */
class LinuxApkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `候选是阿里云与清华 官方源只作回退`() {
        assertEquals(listOf("aliyun", "tuna"), LinuxApk.CANDIDATES.map { it.name })
        assertTrue(LinuxApk.ALIYUN.base, LinuxApk.ALIYUN.base.startsWith("https://mirrors.aliyun.com/alpine/v3.20"))
        assertTrue(LinuxApk.TUNA.base, LinuxApk.TUNA.base.startsWith("https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20"))
        assertEquals("https://dl-cdn.alpinelinux.org/alpine/v3.20", LinuxApk.FALLBACK.base)
        // 探测清单里不含官方源：它不该再等一次超时
        assertFalse(LinuxApk.CANDIDATES.contains(LinuxApk.OFFICIAL))
    }

    @Test
    fun `探测脚本带超时 并按架构打索引文件`() {
        val script = LinuxApk.probeScript("aarch64")
        assertTrue(script, script.contains("-m 3"))
        assertTrue(script, script.contains("main/aarch64/APKINDEX.tar.gz"))
        assertTrue(script, script.contains(LinuxApk.MIRROR_BEGIN))
        assertTrue(script, script.contains(LinuxApk.MIRROR_END))
        LinuxApk.CANDIDATES.forEach { assertTrue(script, script.contains(it.base)) }
    }

    @Test
    fun `最快且可达的镜像胜出`() {
        val out = """
            #APK-MIRROR-BEGIN
            #APK-MIRROR name=aliyun base=https://mirrors.aliyun.com/alpine/v3.20 time=0.512
            #APK-MIRROR name=tuna base=https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20 time=0.180
            #APK-MIRROR-END
        """.trimIndent()
        val probes = LinuxApk.parseProbes(out)
        assertEquals(2, probes.size)
        assertEquals("tuna", LinuxApk.choose(probes).name)
    }

    @Test
    fun `探测超时或全失败时回退官方源`() {
        val out = """
            #APK-MIRROR name=aliyun base=https://mirrors.aliyun.com/alpine/v3.20 time=
            #APK-MIRROR name=tuna base=https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20 time=0.0
        """.trimIndent()
        val probes = LinuxApk.parseProbes(out)
        assertTrue(probes.all { it.seconds == null })
        assertEquals(LinuxApk.OFFICIAL, LinuxApk.choose(probes))
    }

    @Test
    fun `探测没有输出也回退官方源`() {
        assertEquals(LinuxApk.OFFICIAL, LinuxApk.choose(LinuxApk.parseProbes("")))
        assertEquals(LinuxApk.OFFICIAL, LinuxApk.choose(emptyList()))
    }

    @Test
    fun `repositories 同时写 main 与 community 且幂等`() {
        val rootfs = tmp.newFolder("rootfs")
        assertTrue(LinuxApk.applyTo(rootfs, LinuxApk.ALIYUN.base))
        val body = File(rootfs, LinuxApk.REPOSITORIES_PATH).readText()
        assertEquals(LinuxApk.ALIYUN.base + "/main\n" + LinuxApk.ALIYUN.base + "/community\n", body)
        assertFalse("内容没变不该重写", LinuxApk.applyTo(rootfs, LinuxApk.ALIYUN.base))
    }

    @Test
    fun `ABI 映射到 alpine 架构名`() {
        assertEquals("aarch64", LinuxApk.alpineArch("arm64"))
        assertEquals("aarch64", LinuxApk.alpineArch("arm64-v8a"))
        assertEquals("x86_64", LinuxApk.alpineArch("x86_64"))
        assertEquals("armv7", LinuxApk.alpineArch("armv7"))
        assertEquals("x86", LinuxApk.alpineArch("x86"))
    }
}
