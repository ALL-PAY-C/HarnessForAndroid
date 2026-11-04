package dev.dsh.mobile.core.common.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LKG 差异计划（T9.12 §3.3）：回滚只写差异键，keep 保留用户后来新增的键。
 * 每条断言都对应一条「回滚会丢用户设置」的具体失败形态。
 */
class LkgPlannerTest {

    private fun snapshot(entries: Map<String, String>, version: Int = 1, versionCode: Int = 7) =
        SettingsSnapshot.of(version = version, at = 1_700_000_000_000L, versionCode = versionCode, entries = entries)

    @Test
    fun sameValuesNeedNoRestore() {
        val lkg = snapshot(mapOf("theme" to "dark", "font" to "15"))
        val plan = LkgPlanner.plan(lkg, mapOf("theme" to "dark", "font" to "15"))
        assertTrue(plan.restore.isEmpty())
        assertTrue(plan.isEmpty)
        assertTrue(plan.keep.isEmpty())
        assertTrue(plan.dropped.isEmpty())
    }

    @Test
    fun changedValueIsRestored() {
        val lkg = snapshot(mapOf("heartbeat" to "15000", "theme" to "dark"))
        val plan = LkgPlanner.plan(lkg, mapOf("heartbeat" to "0", "theme" to "dark"))
        assertEquals(mapOf("heartbeat" to "15000"), plan.restore)
        assertFalse(plan.isEmpty)
        // 值没变的键一个都不能进 restore：写回它等于把用户当前的正确值也覆盖一遍。
        assertFalse(plan.restore.containsKey("theme"))
    }

    @Test
    fun keysAddedAfterSnapshotAreKept() {
        val lkg = snapshot(mapOf("theme" to "dark"))
        val plan = LkgPlanner.plan(lkg, mapOf("theme" to "dark", "crash.upload.enabled" to "true"))
        assertEquals(listOf("crash.upload.enabled"), plan.keep)
        assertTrue(plan.restore.isEmpty())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun keysMissingFromCurrentAreDroppedAndRestored() {
        val lkg = snapshot(mapOf("theme" to "dark", "proxy" to "http://127.0.0.1:7890"))
        val plan = LkgPlanner.plan(lkg, mapOf("theme" to "dark"))
        assertEquals(listOf("proxy"), plan.dropped)
        assertEquals(mapOf("proxy" to "http://127.0.0.1:7890"), plan.restore)
    }

    @Test
    fun emptySnapshotIsEmptyPlan() {
        val lkg = snapshot(emptyMap())
        val plan = LkgPlanner.plan(lkg, emptyMap())
        assertTrue(plan.isEmpty)
        assertTrue(plan.restore.isEmpty())
        assertTrue(plan.dropped.isEmpty())
        assertEquals(1, plan.targetVersion)
    }

    @Test
    fun hashIgnoresMapInsertionOrder() {
        val a = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val b = linkedMapOf("c" to "3", "a" to "1", "b" to "2")
        assertEquals(LkgPlanner.hashOf(a), LkgPlanner.hashOf(b))
        // 值变了 hash 必须变，否则 writeSnapshot 的「相同则跳过」会把新配置当旧配置丢掉。
        assertTrue(LkgPlanner.hashOf(a) != LkgPlanner.hashOf(a + ("a" to "9")))
    }

    @Test
    fun jsonRoundTripKeepsVersionCode() {
        val lkg = snapshot(mapOf("theme" to "dark", "endpoint" to "http://10.0.2.2:8080"), version = 4, versionCode = 1234)
        val back = SettingsSnapshot.fromJson(lkg.toJson())
        assertEquals(lkg, back)
        assertEquals(1234, back?.versionCode)
        assertEquals(4, back?.version)
    }

    @Test
    fun largeValueSurvivesRoundTrip() {
        val big = "x".repeat(200_000)
        val lkg = snapshot(mapOf("session.title" to big))
        val back = SettingsSnapshot.fromJson(lkg.toJson())
        assertEquals(big.length, back?.entries?.get("session.title")?.length)
        assertEquals(big, back?.entries?.get("session.title"))
    }

    @Test
    fun specialCharactersSurviveRoundTrip() {
        val nasty = "line1\nline2\t\"quoted\" \\ backslash \u0001 end"
        val lkg = snapshot(mapOf("k" to nasty))
        val back = SettingsSnapshot.fromJson(lkg.toJson())
        assertEquals(nasty, back?.entries?.get("k"))
        // 键本身也可能带引号（键名来自 DataStore，不做假设）。
        val quotedKey = snapshot(mapOf("we\"ird" to "v"))
        assertEquals("v", SettingsSnapshot.fromJson(quotedKey.toJson())?.entries?.get("we\"ird"))
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(SettingsSnapshot.fromJson(""))
        assertNull(SettingsSnapshot.fromJson("{\"version\":1}"))
        assertNull(SettingsSnapshot.fromJson("not json at all"))
    }
}
