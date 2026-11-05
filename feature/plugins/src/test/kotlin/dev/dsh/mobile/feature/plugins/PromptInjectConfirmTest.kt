package dev.dsh.mobile.feature.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提示词注入开关的确认闸：只拦「由关变开」，确认态按插件 id 落盘，关掉即摘。 */
class PromptInjectConfirmTest {

    private fun writes(effect: PromptToggleEffect?): Map<String, String> =
        (effect as PromptToggleEffect.Write).entries.toMap()

    private val alpha = "pl.demo.alpha"
    private val beta = "pl.demo.beta"
    private val legacy = "config.demo.confirmed"

    @Test
    fun firstEnableAsksInsteadOfWriting() {
        val effect = promptToggleEffect(alpha, PROMPT_ENABLED_KEY, next = true, confirmedIds = emptySet())
        assertTrue("未确认的首次开启要先弹确认", effect is PromptToggleEffect.AskConfirm)
    }

    @Test
    fun gateFollowsTheKeyNotThePluginId() {
        val effect = promptToggleEffect("pl.anything", PROMPT_ENABLED_KEY, next = true, confirmedIds = emptySet())
        assertTrue("任何插件声明了这条开关都归闸管", effect is PromptToggleEffect.AskConfirm)
    }

    @Test
    fun confirmWritesEnabledAndPluginId() {
        val w = writes(promptConfirmAccepted(alpha, emptySet()))
        assertEquals("true", w[PROMPT_ENABLED_KEY])
        assertEquals(alpha, w[PROMPT_CONFIRMED_KEY])
    }

    @Test
    fun confirmKeepsOtherConfirmedIds() {
        val w = writes(promptConfirmAccepted(beta, setOf(alpha)))
        assertEquals("$alpha,$beta", w[PROMPT_CONFIRMED_KEY])
    }

    @Test
    fun cancelWritesNothing() {
        assertEquals("取消一个字都不写", emptyMap<String, String>(), writes(promptConfirmCancelled()))
    }

    @Test
    fun confirmedEnableOpensWithoutAsking() {
        val w = writes(promptToggleEffect(alpha, PROMPT_ENABLED_KEY, next = true, confirmedIds = setOf(alpha)))
        assertEquals("true", w[PROMPT_ENABLED_KEY])
        assertEquals(alpha, w[PROMPT_CONFIRMED_KEY])
    }

    @Test
    fun turningOffDropsOwnIdSoNextEnableAsksAgain() {
        val w = writes(promptToggleEffect(alpha, PROMPT_ENABLED_KEY, next = false, confirmedIds = setOf(alpha, beta)))
        assertEquals("false", w[PROMPT_ENABLED_KEY])
        assertEquals("另一个插件的确认态不受影响", beta, w[PROMPT_CONFIRMED_KEY])
        assertTrue(
            "关掉后再开必须重新弹",
            promptToggleEffect(alpha, PROMPT_ENABLED_KEY, next = true, confirmedIds = emptySet())
                is PromptToggleEffect.AskConfirm,
        )
    }

    @Test
    fun otherKeysBypassTheGate() {
        assertNull(promptToggleEffect(alpha, "note", next = true, confirmedIds = emptySet()))
    }

    @Test
    fun newKeyAloneMeansConfirmed() {
        val read = readConfirmation(alpha, mapOf(PROMPT_CONFIRMED_KEY to alpha))
        assertEquals(setOf(alpha), read.ids)
        assertEquals("已经迁过就不再写", emptyList<Pair<String, String>>(), read.migrate)
    }

    @Test
    fun legacyKeyMigratesToNewKeyAndIsCleared() {
        val read = readConfirmation(alpha, mapOf(legacy to "true"))
        assertEquals(setOf(alpha), read.ids)
        assertEquals(
            "新键写本插件 id、旧键清空",
            listOf(PROMPT_CONFIRMED_KEY to alpha, legacy to ""),
            read.migrate,
        )
    }

    @Test
    fun repeatedMigrationChangesNothing() {
        val first = readConfirmation(alpha, mapOf(legacy to "true"))
        val migrated = mapOf(PROMPT_CONFIRMED_KEY to alpha, legacy to "")
        val again = readConfirmation(alpha, migrated)
        assertEquals(setOf(alpha), again.ids)
        assertEquals("重复迁移必须无写入", emptyList<Pair<String, String>>(), again.migrate)
        assertEquals("首轮迁移写入的就是上面那份存储", migrated, first.migrate.toMap())
    }

    @Test
    fun bothKeysPresentKeepsNewAndClearsLegacy() {
        val read = readConfirmation(alpha, mapOf(PROMPT_CONFIRMED_KEY to beta, legacy to "true"))
        assertEquals(setOf(beta), read.ids)
        assertEquals("新键为准，旧键只清不覆盖", listOf(legacy to ""), read.migrate)
    }

    @Test
    fun neitherKeyMeansUnconfirmedWithoutWrites() {
        val read = readConfirmation(alpha, mapOf(PROMPT_ENABLED_KEY to "false"))
        assertEquals(emptySet<String>(), read.ids)
        assertEquals(emptyList<Pair<String, String>>(), read.migrate)
    }

    @Test
    fun idsRoundTripThroughCommas() {
        assertEquals(setOf(alpha, beta), confirmedIds(encodeConfirmedIds(setOf(beta, alpha))))
        assertEquals(emptySet<String>(), confirmedIds(" , "))
        assertEquals(emptySet<String>(), confirmedIds(null))
    }
}
