package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * TerminalEmulator 的纯函数行为（T9.14 修 5）。
 *
 * 只喂字节、只读屏幕/光标/样式，不碰 View、不碰 renderer、不碰 PTY。
 * 客户端传 null（Logger 走 android.util.Log，靠 unitTests.isReturnDefaultValues 变成空实现），
 * 反向输出用 Recorder 接住，用来验证 OSC 标题真的回调出去了。
 */
class TerminalEmulatorTest {

    private val esc = "\u001b"

    private class Recorder : TerminalOutput() {
        var lastTitle: String? = null
        var titleChanges = 0
        var bells = 0

        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            lastTitle = newTitle
            titleChanges++
        }

        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() {
            bells++
        }

        override fun onColorsChanged() = Unit
    }

    private val recorder = Recorder()

    private fun emulator(columns: Int = 10, rows: Int = 5): TerminalEmulator =
        TerminalEmulator(recorder, columns, rows, 10, 20, 100, null)

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        append(bytes, bytes.size)
    }

    private fun TerminalEmulator.line(row: Int, columns: Int = 10): String =
        getScreen().getSelectedText(0, row, columns, row)

    @Test
    fun printingAdvancesCursorAndWritesText() {
        val emulator = emulator()
        emulator.feed("abc")
        assertEquals(0, emulator.getCursorRow())
        assertEquals(3, emulator.getCursorCol())
        assertEquals("abc", emulator.line(0))
    }

    @Test
    fun carriageReturnAndLineFeedMoveTheCursor() {
        val emulator = emulator()
        emulator.feed("abc\r\n")
        assertEquals(1, emulator.getCursorRow())
        assertEquals(0, emulator.getCursorCol())
        assertEquals("abc", emulator.line(0))
    }

    @Test
    fun lineFeedAtTheBottomRowScrollsTheScreen() {
        val emulator = emulator(columns = 10, rows = 5)
        emulator.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(4, emulator.getCursorRow())
        emulator.feed("\r\n")
        assertEquals(4, emulator.getCursorRow())
        assertEquals("2", emulator.line(0).trim())
        assertEquals("5", emulator.line(3).trim())
        assertEquals("", emulator.line(4).trim())
    }

    @Test
    fun cursorUpMovesByCountAndClampsAtTheTopRow() {
        val emulator = emulator()
        emulator.feed("\n\n\n")
        assertEquals(3, emulator.getCursorRow())
        emulator.feed(esc + "[2A")
        assertEquals(1, emulator.getCursorRow())
        emulator.feed(esc + "[99A")
        assertEquals(0, emulator.getCursorRow())
    }

    @Test
    fun cursorDownMovesByCountAndClampsAtTheLastRow() {
        val emulator = emulator(columns = 10, rows = 5)
        emulator.feed(esc + "[2B")
        assertEquals(2, emulator.getCursorRow())
        emulator.feed(esc + "[99B")
        assertEquals(4, emulator.getCursorRow())
    }

    @Test
    fun cursorForwardAndBackAreClampedToTheMargins() {
        val emulator = emulator()
        emulator.feed(esc + "[3C")
        assertEquals(3, emulator.getCursorCol())
        emulator.feed(esc + "[2D")
        assertEquals(1, emulator.getCursorCol())
        emulator.feed(esc + "[99D")
        assertEquals(0, emulator.getCursorCol())
        emulator.feed(esc + "[99C")
        assertEquals(9, emulator.getCursorCol())
    }

    @Test
    fun boldAttributeAppliesOnlyToTheFollowingText() {
        val emulator = emulator()
        emulator.feed(esc + "[1mX")
        val boldStyle = emulator.getScreen().getStyleAt(0, 0)
        assertTrue(TextStyle.decodeEffect(boldStyle) and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0)
        emulator.feed(esc + "[0mY")
        val normalStyle = emulator.getScreen().getStyleAt(0, 1)
        assertFalse(TextStyle.decodeEffect(normalStyle) and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0)
    }

    @Test
    fun sgrSetsIndexedForegroundColor() {
        val emulator = emulator()
        emulator.feed(esc + "[31mX")
        assertEquals(1, TextStyle.decodeForeColor(emulator.getScreen().getStyleAt(0, 0)))
    }

    @Test
    fun sgrSets256ColorForegroundIndex() {
        val emulator = emulator()
        emulator.feed(esc + "[38;5;200mX")
        assertEquals(200, TextStyle.decodeForeColor(emulator.getScreen().getStyleAt(0, 0)))
    }

    @Test
    fun eraseToEndOfLineClearsTheTail() {
        val emulator = emulator()
        emulator.feed("abcdef")
        emulator.feed("\r" + esc + "[3C" + esc + "[K")
        assertEquals("abc", emulator.line(0))
    }

    @Test
    fun eraseDisplayClearsEveryRow() {
        val emulator = emulator()
        emulator.feed("a\nb")
        emulator.feed(esc + "[2J")
        assertEquals("", emulator.getScreen().getTranscriptText())
    }

    @Test
    fun oscSetsTheTitleAndNotifiesTheClient() {
        val emulator = emulator()
        emulator.feed(esc + "]0;hello\u0007")
        assertEquals("hello", emulator.title)
        assertEquals("hello", recorder.lastTitle)
        assertEquals(1, recorder.titleChanges)
    }

    @Test
    fun autowrapMovesToTheNextRowWhenTheLineIsFull() {
        val emulator = emulator(columns = 10, rows = 5)
        emulator.feed("0123456789X")
        assertEquals(1, emulator.getCursorRow())
        assertEquals("X", emulator.line(1))
        assertTrue(emulator.getScreen().getLineWrap(0))
    }

    @Test
    fun wideCharacterOccupiesTwoColumns() {
        val emulator = emulator()
        emulator.feed("\u4e2d")
        assertEquals(2, emulator.getCursorCol())
        assertEquals("\u4e2d", emulator.line(0))
    }

    @Test
    fun writingPastTheLastCellDoesNotThrow() {
        val emulator = emulator(columns = 10, rows = 5)
        emulator.feed("x".repeat(500))
        assertEquals(4, emulator.getCursorRow())
        assertTrue(emulator.getCursorCol() in 0..9)
    }

    @Test
    fun wcWidthReportsDisplayWidth() {
        assertEquals(1, WcWidth.width('a'.code))
        assertEquals(2, WcWidth.width(0x4E2D))
        assertEquals(0, WcWidth.width(0x0301))
    }
}