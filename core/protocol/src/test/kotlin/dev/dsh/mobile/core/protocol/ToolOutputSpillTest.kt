package dev.dsh.mobile.core.protocol

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-4：超阈值落盘 + 头尾 + locator + 按 offset/limit 读回。 */
class ToolOutputSpillTest {
    private val root: File = Files.createTempDirectory("spill-test").toFile()

    private fun spill(threshold: Int = 100) = ToolOutputSpill(rootProvider = { root }, threshold = threshold)

    @Test
    fun smallOutputPassesThroughUntouched() {
        val out = spill().present("短输出", "t1", "c1")
        assertEquals("短输出", out)
        assertFalse(File(root, "t1/c1.txt").exists())
    }

    @Test
    fun largeOutputGetsSpilledWithHeadTailAndLocator() {
        val body = (1..200).joinToString("\n") { "line-" + it }
        val out = spill().present(body, "t1", "c1")
        // 头尾保留、中间折叠：第一行与最后一行都在，正文远小于原文。
        assertTrue(out, out.contains("line-1\nline-2"))
        assertTrue(out, out.contains("line-200"))
        assertTrue(out, out.contains("…（中间省略"))
        assertTrue(out, out.contains("[工具输出已落盘]"))
        assertTrue(out, out.contains("totalLines=200"))
        assertTrue("正文应远小于原文：" + out.length, out.length < body.length)
        // 落盘文件是全文，字节数与 locator 一致。
        val file = File(root, "t1/c1.txt")
        assertTrue(file.isFile)
        assertEquals(body, file.readText())
        assertTrue(out.contains("totalBytes=" + file.length()))
        assertTrue(out.contains("read_output"))
    }

    @Test
    fun readReturnsRequestedSliceWithHeader() {
        val body = (1..50).joinToString("\n") { "L" + it }
        val locator = spill().spill(body, "t2", "c2")!!
        val slice = spill().read(locator.path, offset = 10, limit = 3)
        assertTrue(slice, slice.contains("共 50 行"))
        assertTrue(slice, slice.contains("本次返回第 11-13 行"))
        assertTrue(slice, slice.endsWith("L11\nL12\nL13"))
        // 越界 offset 退化成空切片，但 head 仍然告诉模型文件有多大。
        val tail = spill().read(locator.path, offset = 999, limit = 5)
        assertTrue(tail, tail.contains("本次返回第 0-0 行"))
    }

    @Test
    fun readRejectsPathsOutsideSpillRoot() {
        val outside = File(root.parentFile, "outside-" + System.nanoTime() + ".txt")
        outside.writeText("别让我读这个")
        val out = spill().read(outside.absolutePath)
        assertTrue(out, out.contains("拒绝回读"))
        assertTrue(!out.contains("别让我读这个"))
    }

    @Test
    fun missingRootFallsBackToPlainTruncation() {
        val out = ToolOutputSpill(rootProvider = { null }, threshold = 10).present("x".repeat(50), "t", "c")
        assertFalse(ToolOutputSpill(rootProvider = { null }).enabled())
        assertTrue(out, out.startsWith("x".repeat(10)))
        assertTrue(out, out.contains("…（已截断，共 50 字符）"))
    }
}
