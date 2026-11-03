package dev.dsh.mobile.toolbridge.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4.1 读侧纯逻辑自测：节点身份、选择器匹配、代次与等待。
 * 不碰 Android 类型 —— AccessibilityNodeInfo 只能在设备上验（见 T41 报告的证据缺口）。
 */
class PhoneTreeReadTest {

    private fun node(
        seq: Int,
        parentIndex: Int = -1,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
    ) = PhoneNode(
        snapshotId = 7L,
        seq = seq,
        parentIndex = parentIndex,
        depth = 0,
        className = className,
        packageName = "com.example.app",
        text = text,
        contentDesc = desc,
        viewId = viewId,
        bounds = PhoneBounds(0, 0, 100, 50),
        clickable = clickable,
        editable = editable,
        scrollable = false,
        checkable = false,
        checked = false,
        selected = false,
        enabled = true,
        focused = false,
        visible = true,
    )

    @Test
    fun nodeIdCarriesSnapshotGeneration() {
        val n = node(3, parentIndex = 1)
        assertEquals("7:3", n.nodeId)
        assertEquals("7:1", n.parentId)
        assertNull(node(0).parentId)
    }

    @Test
    fun snapshotLooksUpByNodeId() {
        val snap = PhoneSnapshot(7L, listOf(node(0), node(1, parentIndex = 0)))
        assertEquals(1, snap.byId("7:1")?.seq)
        assertNull(snap.byId("8:1"))
        assertNull(snap.byId("7:x"))
    }

    @Test
    fun selectorFieldsAreAnded() {
        val sel = PhoneSelector.parse(JSONObject().put("textContains", "搜索").put("clickable", true))
        assertTrue(sel!!.matches(node(0, text = "搜索设置", clickable = true)))
        assertFalse(sel.matches(node(1, text = "搜索设置", clickable = false)))
        assertFalse(sel.matches(node(2, text = "别的", clickable = true)))
    }

    @Test
    fun selectorParsesViewIdSuffixAndStaysNullWhenEmpty() {
        assertNull(PhoneSelector.parse(null))
        val sel = PhoneSelector.parse(JSONObject().put("viewIdSuffix", "search").put("text", ""))!!
        assertEquals("search", sel.viewIdSuffix)
        assertNull(sel.text)
        assertTrue(sel.matches(node(0, viewId = "com.android.settings:id/search")))
        assertFalse(sel.matches(node(1, viewId = "com.android.settings:id/search_bar")))
    }

    @Test
    fun selectorIndexPicksNthMiss() {
        val nodes = listOf(node(0, text = "a"), node(1, text = "a"), node(2, text = "a"))
        val sel = PhoneSelector(text = "a", index = 1)
        assertEquals(listOf(1), sel.select(nodes).map { it.seq })
        assertTrue(PhoneSelector(text = "a", index = 9).select(nodes).isEmpty())
        assertEquals(0, PhoneSelector(text = "a").first(nodes)?.seq)
    }

    @Test
    fun emptySelectorMatchesEverything() {
        val sel = PhoneSelector()
        assertTrue(sel.isEmpty)
        assertEquals(2, sel.select(listOf(node(0), node(1))).size)
    }

    @Test
    fun generationOnlyBumpsOnFingerprintChange() {
        val g = PhoneTreeGenerations()
        assertEquals(0L, g.current())
        assertEquals(1L, g.commit(10))
        // 内容变化事件会毫秒级重放同一棵树：同指纹不换代，否则 awaitChange 会立刻返回。
        assertNull(g.commit(10))
        assertEquals(1L, g.current())
        assertEquals(2L, g.commit(11))
        assertEquals(3L, g.bump())
    }

    @Test
    fun awaitChangeReturnsImmediatelyWhenAlreadyAdvanced() = runBlocking {
        val g = PhoneTreeGenerations()
        g.bump()
        g.bump()
        assertEquals(2L, g.awaitChange(1L, 50L))
    }

    @Test
    fun awaitChangeTimesOutWithoutChange() = runBlocking {
        val g = PhoneTreeGenerations()
        g.bump()
        assertNull(g.awaitChange(1L, 60L))
    }

    @Test
    fun awaitChangeWakesOnLaterBump() = runBlocking {
        val g = PhoneTreeGenerations()
        val since = g.current()
        val t = Thread { Thread.sleep(40); g.bump() }
        t.start()
        assertEquals(since + 1, g.awaitChange(since, 2000L))
        t.join()
    }

    @Test
    fun readSideErrorCodesRetryable() {
        assertFalse(PhoneErrorCode.A11yOff.retryable)
        assertFalse(PhoneErrorCode.BadArgs.retryable)
        assertTrue(PhoneErrorCode.NodeNotFound.retryable)
        assertTrue(PhoneErrorCode.StaleSnapshot.retryable)
        assertTrue(PhoneErrorCode.Timeout.retryable)
        assertEquals(PhoneErrorCode.Timeout, PhoneErrorCode.of("E_TIMEOUT"))
        assertNull(PhoneErrorCode.of("E_BOUNDS"))
        assertEquals("E_BAD_ARGS", phoneErrorJson(PhoneErrorCode.BadArgs, "x").getString("code"))
        assertFalse(phoneErrorJson(PhoneErrorCode.BadArgs, "x").getBoolean("ok"))
    }
}
