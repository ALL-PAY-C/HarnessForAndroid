package dev.dsh.mobile.feature.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移除技能这条路的纯逻辑自检：请求体、确认框文案（内置技能的「下次启动会重新铺回」必须真的出现）、
 * 失败提示要不要原样带引擎的话、内置名来自资产清单的解析。
 *
 * 界面本身不在这里测 —— Compose 得跑 instrument，本模块只有 JVM 单测。
 */
class SkillRemoveTest {

    @Test
    fun removePayloadCarriesNameOnly() {
        val payload = removePayload("code-review")
        assertEquals("code-review", payload.optString("name"))
        assertEquals(setOf("name"), payload.keys().asSequence().toSet())
    }

    @Test
    fun bundledConfirmBodyWarnsAboutReseeding() {
        val body = removeConfirmBody("code-review", bundled = true)
        assertTrue(body.contains("code-review"))
        assertTrue(body.contains("下次启动会重新铺回"))
    }

    @Test
    fun userSkillConfirmBodyHasNoReseedWarning() {
        val body = removeConfirmBody("my-own-skill", bundled = false)
        assertTrue(body.contains("my-own-skill"))
        assertFalse(body.contains("重新铺回"))
    }

    @Test
    fun failureNoticeKeepsEngineWording() {
        assertEquals("移除失败：未知技能: \"ghost\"", removeFailureNotice("未知技能: \"ghost\""))
        assertEquals("移除失败：未知技能: \"ghost\"", removeFailureNotice("  未知技能: \"ghost\"  "))
    }

    @Test
    fun failureNoticeFallsBackWhenEngineSaysNothing() {
        assertEquals("移除失败：引擎拒绝了这次移除", removeFailureNotice(""))
        assertEquals("移除失败：引擎拒绝了这次移除", removeFailureNotice("   \n "))
    }

    @Test
    fun successNoticeNamesTheSkill() {
        assertEquals("已移除 code-review", removedNotice("code-review"))
    }

    @Test
    fun bundledSetComesFromAssetListing() {
        val set = bundledSkillSet(
            arrayOf("code-review", " commit-message ", "", "perf-triage", "systematic-debugging", "test-first"),
        )
        assertEquals(
            setOf("code-review", "commit-message", "perf-triage", "systematic-debugging", "test-first"),
            set,
        )
        assertTrue("code-review" in set)
        assertFalse("my-own-skill" in set)
    }

    @Test
    fun bundledSetDegradesToEmptyWithoutAssets() {
        assertEquals(emptySet<String>(), bundledSkillSet(null))
        assertEquals(emptySet<String>(), bundledSkillSet(emptyArray()))
    }
}
