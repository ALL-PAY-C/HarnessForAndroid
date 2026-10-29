package dev.dsh.mobile.toolbridge

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FileBridge 的白名单 / 越权 / 上限行为。
 *
 * 全是纯 JVM 断言：FileBridge 不 import android.*，权限探测由 lambda 注入，
 * 所以这里既不需要 Context，也不需要 Robolectric。
 */
class FileBridgeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var outside: File

    /** 白名单里唯一的那条根：用临时目录冒充 /sdcard。 */
    private fun rootPath(): String = key(root)

    private fun bridge(maxBytes: Long = FileBridge.MAX_BYTES) =
        FileBridge(roots = listOf(rootPath()), maxBytes = maxBytes, storageAccess = { true })

    @Before
    fun setUp() {
        root = temp.newFolder("allow")
        outside = temp.newFolder("outside")
    }

    // —— 白名单放行 ——

    @Test
    fun writeThenReadRoundTripInsideWhitelist() {
        val bridge = bridge()
        val target = rootPath() + "/a/b.txt"

        assertOk(bridge.write(target, "第一行\n第二行\n"))
        val read = bridge.read(target)

        assertOk(read)
        assertTrue(read, read.contains("第一行"))
        assertTrue(read, read.contains("\"length\":"))
    }

    @Test
    fun appendKeepsPreviousContent() {
        val bridge = bridge()
        val target = rootPath() + "/log.txt"

        assertOk(bridge.write(target, "a"))
        assertOk(bridge.write(target, "b", append = true))

        val read = bridge.read(target)
        assertTrue(read, read.contains("\"content\":\"ab\""))
    }

    // —— 白名单拒绝 ——

    @Test
    fun outsideWhitelistIsPermissionDenied() {
        File(outside, "secret.txt").writeText("nope")
        val denied = bridge().read(key(outside) + "/secret.txt")

        assertFalse(denied.contains("\"ok\":true"))
        assertCode(denied, FsCode.PERMISSION_DENIED)
        assertTrue(denied, denied.contains("不在可访问范围"))
    }

    @Test
    fun relativePathIsRejected() {
        val denied = bridge().read("a.txt")
        assertCode(denied, FsCode.PERMISSION_DENIED)
    }

    @Test
    fun dotDotEscapeIsRejected() {
        val bridge = bridge()
        val escaped = rootPath() + "/../outside/secret.txt"

        assertCode(bridge.read(escaped), FsCode.PERMISSION_DENIED)
        assertCode(bridge.read(rootPath() + "/sub/../../outside/x.txt"), FsCode.PERMISSION_DENIED)
        assertCode(bridge.write(escaped, "x"), FsCode.PERMISSION_DENIED)
        assertCode(bridge.op("delete", escaped), FsCode.PERMISSION_DENIED)
    }

    @Test
    fun dotDotInsideWhitelistStillResolves() {
        // 合法用法不能被「有 ..」一棍子打死：折叠后仍在白名单内就放行。
        File(root, "inner").mkdirs()
        File(root, "inner/keep.txt").writeText("kept")

        val read = bridge().read(rootPath() + "/inner/../inner/keep.txt")
        assertOk(read)
        assertTrue(read, read.contains("kept"))
    }

    @Test
    fun symlinkPointingOutsideWhitelistIsRejected() {
        File(outside, "leak.txt").writeText("leaked")
        val link = File(root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            // Windows 上创建符号链接要管理员/开发者模式；没有就跳过，别假装验证过。
            Assume.assumeTrue("该平台不允许创建符号链接：" + e.message, false)
            return
        }

        val denied = bridge().read(rootPath() + "/link/leak.txt")
        assertCode(denied, FsCode.PERMISSION_DENIED)
    }

    // —— 64MB 上限 ——

    @Test
    fun defaultLimitIs64Mb() {
        assertEquals(64L * 1024 * 1024, FileBridge.MAX_BYTES)
    }

    @Test
    fun readOverLimitAsksForSegments() {
        val bridge = bridge(maxBytes = 16)
        val target = File(root, "big.txt")
        target.writeBytes(ByteArray(32))

        val denied = bridge.read(target.absolutePath.replace('\\', '/'))
        assertCode(denied, FsCode.CONTENT_TOO_LARGE)
        assertTrue(denied, denied.contains("内容过大，请分段读取"))
    }

    @Test
    fun readWithOffsetAndLimitWithinCapSucceeds() {
        val bridge = bridge(maxBytes = 16)
        val target = File(root, "big.txt")
        target.writeBytes("0123456789abcdefghij".toByteArray(Charsets.UTF_8))

        val part = bridge.read(key(target), offset = 10, limit = 5)
        assertOk(part)
        assertTrue(part, part.contains("\"content\":\"abcde\""))
        assertTrue(part, part.contains("\"truncated\":true"))
    }

    @Test
    fun writeOverLimitAsksForSegments() {
        val bridge = bridge(maxBytes = 16)
        val denied = bridge.write(rootPath() + "/x.txt", "0123456789abcdefg") // 17 字节

        assertCode(denied, FsCode.CONTENT_TOO_LARGE)
        assertTrue(denied, denied.contains("内容过大"))
        assertFalse(File(root, "x.txt").exists())
    }

    // —— fs_list ——

    @Test
    fun listIsShallowByDefaultAndRecursiveOnDemand() {
        val bridge = bridge()
        File(root, "sub").mkdirs()
        File(root, "a.txt").writeText("a")
        File(root, "sub/b.txt").writeText("b")

        val shallow = bridge.list(rootPath())
        assertOk(shallow)
        assertTrue(shallow, shallow.contains("\"name\":\"a.txt\""))
        assertFalse(shallow, shallow.contains("b.txt"))

        val deep = bridge.list(rootPath(), recursive = true)
        assertTrue(deep, deep.contains("b.txt"))
    }

    @Test
    fun listOnFileIsAnExplicitError() {
        File(root, "a.txt").writeText("a")
        assertCode(bridge().list(rootPath() + "/a.txt"), FsCode.INVALID_ARGUMENT)
    }

    // —— fs_op ——

    @Test
    fun opsCoverMkdirStatCopyMoveDelete() {
        val bridge = bridge()
        val dir = rootPath() + "/work"

        assertOk(bridge.op("mkdir", dir))
        assertOk(bridge.write("$dir/a.txt", "abc"))

        val stat = bridge.op("stat", "$dir/a.txt")
        assertOk(stat)
        assertTrue(stat, stat.contains("\"size\":3"))

        assertOk(bridge.op("copy", "$dir/a.txt", "$dir/b.txt"))
        assertTrue(File(root, "work/b.txt").readText() == "abc")

        assertOk(bridge.op("move", "$dir/b.txt", "$dir/c.txt"))
        assertTrue(File(root, "work/c.txt").exists())
        assertFalse(File(root, "work/b.txt").exists())

        assertOk(bridge.op("delete", "$dir/a.txt"))
        assertFalse(File(root, "work/a.txt").exists())
    }

    @Test
    fun deletingWhitelistRootIsRefused() {
        assertCode(bridge().op("delete", rootPath()), FsCode.INVALID_ARGUMENT)
        assertTrue(root.isDirectory)
    }

    @Test
    fun unknownOpIsExplicit() {
        assertCode(bridge().op("chmod", rootPath() + "/x"), FsCode.UNSUPPORTED_OP)
    }

    @Test
    fun statOnMissingPathCarriesCodeNotJustFalse() {
        val stat = bridge().op("stat", rootPath() + "/nope")
        assertCode(stat, FsCode.NOT_FOUND)
        assertTrue(stat, stat.contains("\"exists\":false"))
    }

    // —— 结构化返回值 ——

    @Test
    fun everyFailureIsStructuredNeverSilent() {
        val bridge = bridge()
        val failures = listOf(
            bridge.read(""),
            bridge.read(key(outside) + "/x"),
            bridge.read(rootPath() + "/missing.txt"),
            bridge.write("relative.txt", "x"),
            bridge.list(rootPath() + "/missing"),
            bridge.op("move", rootPath() + "/a", null),
        )
        for (json in failures) {
            assertTrue(json, json.startsWith("{"))
            assertTrue(json, json.contains("\"ok\":false"))
            assertTrue(json, json.contains("\"code\":"))
            assertTrue(json, json.contains("\"error\":"))
        }
    }

    @Test
    fun fullFileAccessComesFromInjectedProbe() {
        assertTrue(FileBridge(roots = listOf(rootPath()), storageAccess = { true }).hasFullFileAccess())
        assertFalse(FileBridge(roots = listOf(rootPath()), storageAccess = { false }).hasFullFileAccess())
        // 探针自己炸了也只当「没有权限」，不把异常抛给上层。
        assertFalse(FileBridge(roots = listOf(rootPath()), storageAccess = { error("boom") }).hasFullFileAccess())
    }

    @Test
    fun defaultRootsCoverExternalStorageAndBothPrivateAliases() {
        val roots = FileBridge.DEFAULT_ROOTS
        // 外部存储三处别名都要在：模型可能从任意一处拿到路径。
        assertTrue(roots.contains("/sdcard"))
        assertTrue(roots.contains("/storage/emulated/0"))
        assertTrue(roots.contains("/data/media/0"))
        // 私有目录两个别名都要在：filesDir 给的是 /data/user/0，/data/data 只是它的符号链接。
        assertTrue(roots.contains("/data/data/dev.dsh.mobile"))
        assertTrue(roots.contains("/data/user/0/dev.dsh.mobile"))
    }

    @Test
    fun maxBytesAndEntryCapAreTheAgreedNumbers() {
        assertEquals(64L * 1024 * 1024, FileBridge.MAX_BYTES)
        assertEquals(1000, FileBridge.MAX_ENTRIES)
    }

    // —— 断言助手：MiniJson 没有解析器，直接查关键字，够用且不带来新依赖 ——

    private fun assertOk(json: String) = assertTrue("期望 ok=true，实际：" + json, json.contains("\"ok\":true"))

    private fun assertCode(json: String, code: String) =
        assertTrue("期望 code=" + code + "，实际：" + json, json.contains("\"code\":\"" + code + "\""))

    private fun key(file: File): String = file.absolutePath.replace('\\', '/')
}
