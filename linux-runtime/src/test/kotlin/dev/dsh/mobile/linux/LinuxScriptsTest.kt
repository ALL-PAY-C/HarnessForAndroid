package dev.dsh.mobile.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T5：两个 guest 脚本的形状。设备侧真正跑的是这两段文本，所以逐条钉住关键行。 */
class LinuxScriptsTest {

    private val provision = LinuxScripts.provision()
    private val selfCheck = LinuxScripts.selfCheck()

    @Test
    fun `必需工具清单固定`() {
        assertEquals(
            listOf("python3", "py3-pip", "git", "curl", "wget", "ca-certificates"),
            LinuxScripts.required(),
        )
    }

    @Test
    fun `预置脚本先查已装 缺什么才装什么`() {
        assertTrue(provision, provision.contains("REQ='python3 py3-pip git curl wget ca-certificates'"))
        assertTrue(provision, provision.contains("apk info -e \"\$p\""))
        assertTrue(provision, provision.contains("apk add --no-cache \$missing"))
        // 全齐时直接跳过，不联网
        assertTrue(provision, provision.contains("#T5-STATUS ok=1 skipped=1 apk_rc=-"))
    }

    @Test
    fun `成功判据是 apk info 逐包校验而不是 apk 退出码`() {
        val judgeAt = provision.indexOf("apk info -e \"\$p\" >/dev/null 2>&1 || still=")
        val statusAt = provision.indexOf("#T5-STATUS ok=1 skipped=0")
        assertTrue(provision, judgeAt in 0 until statusAt)
        assertTrue(provision, provision.contains("still=''"))
    }

    @Test
    fun `失败时回滚 新装包删掉 并恢复 apk world 快照`() {
        assertTrue(provision, provision.contains("cp /etc/apk/world \"\$STATE/world.bak\""))
        assertTrue(provision, provision.contains("apk del \"\$p\""))
        assertTrue(provision, provision.contains("cp \"\$STATE/world.bak\" /etc/apk/world"))
        assertTrue(provision, provision.contains("#T5-ROLLBACK begin missing="))
        assertTrue(provision, provision.contains("#T5-ROLLBACK done"))
        assertTrue(provision, provision.contains("#T5-ERROR missing=\$still"))
    }

    @Test
    fun `apk 原始输出被标记包住 便于事后诊断`() {
        assertTrue(provision, provision.contains("#T5-APK-BEGIN"))
        assertTrue(provision, provision.contains("apk add --no-cache \$missing 2>&1"))
        assertTrue(provision, provision.contains("#T5-APK-END rc=\$apk_rc"))
    }

    @Test
    fun `脚本里没有未展开的模板占位`() {
        // ${D} 之类的 Kotlin 模板必须已经变成真 $；shell 自己的 ${x:-y} 例外。
        assertFalse(provision, provision.contains("\${"))
        assertFalse(provision, provision.contains("\$D"))
        assertFalse(selfCheck, selfCheck.contains("\$D"))
        assertTrue(selfCheck, selfCheck.contains("\${first:-no output}"))
    }

    @Test
    fun `自检脚本覆盖每项工具与网络证据`() {
        listOf(
            "probe python3 python3 -c",
            "probe pip3 pip3 --version",
            "probe git git --version",
            "probe curl curl --version",
            "probe wget wget --version",
            "probe apk apk --version",
            "line ca-certificates",
            "line pip-config",
            "line resolv",
            "line dns",
            "line https",
            "line stderr",
            "line exitcode",
        ).forEach { assertTrue(it, selfCheck.contains(it)) }
        assertTrue(selfCheck, selfCheck.contains("sys.exit(7)"))
        assertTrue(selfCheck, selfCheck.contains(LinuxScripts.DNS_PROBE_HOST))
        assertTrue(selfCheck, selfCheck.contains(LinuxScripts.HTTPS_PROBE_URL))
        assertTrue(selfCheck, selfCheck.contains("#T5-CHECK name=%s ok=%s detail=%s"))
    }

    @Test
    fun `自检脚本只读 不装包不改配置`() {
        assertFalse(selfCheck, selfCheck.contains("apk add"))
        assertFalse(selfCheck, selfCheck.contains("apk del"))
        assertFalse(selfCheck, selfCheck.contains("> /etc/"))
    }

    @Test
    fun `清单 assets 与 Kotlin 常量不漂移`() {
        val candidates = listOf(
            "src/main/assets/linux-artifacts.json",
            "linux-runtime/src/main/assets/linux-artifacts.json",
        )
        val file = candidates.map(::File).firstOrNull { it.isFile }
        assertTrue("找不到 linux-artifacts.json，试过 " + candidates, file != null)
        val json = requireNotNull(file).readText()
        val tools = Regex("\"packages\"\\s*:\\s*\\[([^\\]]*)]").find(json)
        assertTrue("assets 清单里缺 tools.packages", tools != null)
        val packages = requireNotNull(tools).groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        assertEquals(LinuxScripts.required(), packages)
        assertTrue(json, json.contains(LinuxPip.INDEX_URL))
    }
}
