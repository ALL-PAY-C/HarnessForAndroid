package dev.dsh.mobile.core.platform

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T9.11 §2.1 三态归一 + 锁存归一。纯 JVM，不起 Android Context。 */
class UiLocaleTest {

    @Test
    fun tagMappingIsTheOnlyAcceptedShape() {
        assertNull(UiLocale.tagOf(UiLanguage.System))
        assertEquals("zh", UiLocale.tagOf(UiLanguage.Zh))
        assertEquals("en", UiLocale.tagOf(UiLanguage.En))
    }

    @Test
    fun unknownSavedTagFallsBackToSystem() {
        assertNull(UiLocale.resolveTag(null))
        assertNull(UiLocale.resolveTag(""))
        assertNull(UiLocale.resolveTag("fr"))
        assertNull(UiLocale.resolveTag("ZH-Hans"))
        assertEquals("zh", UiLocale.resolveTag("zh"))
        assertEquals(UiLanguage.System, UiLocale.languageOf("zh-CN"))
        assertEquals(UiLanguage.Zh, UiLocale.languageOf("zh"))
        assertEquals(UiLanguage.En, UiLocale.languageOf("en"))
    }

    @Test
    fun systemModeFollowsFirstPreferredLanguage() {
        val zh = Locale.SIMPLIFIED_CHINESE
        assertEquals(zh, UiLocale.localeOf(UiLanguage.System, zh))
        assertEquals(Locale.ENGLISH, UiLocale.localeOf(UiLanguage.System, Locale.US))
        assertEquals(zh, UiLocale.localeOf(UiLanguage.System, Locale.TRADITIONAL_CHINESE))
        assertEquals(Locale.ENGLISH, UiLocale.localeOf(UiLanguage.System, Locale.JAPAN))
    }

    @Test
    fun explicitChoiceNeverFollowsTheSystem() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, UiLocale.localeOf(UiLanguage.Zh, Locale.US))
        assertEquals(Locale.ENGLISH, UiLocale.localeOf(UiLanguage.En, Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun latchKeepsTheLastWrittenTag() {
        UiLocale.latchTag("en")
        assertEquals(UiLanguage.En, UiLocale.language())
        assertEquals(Locale.ENGLISH, UiLocale.locale())
        UiLocale.latchTag("zh")
        assertEquals(UiLanguage.Zh, UiLocale.language())
    }

    @Test
    fun missingTagMeansDefaultChinese() {
        // 键不存在 = 用户没选过 → 默认中文（与「显式跟随系统」是两件事）。
        assertEquals("zh", UiLocale.effectiveTag(null))
        UiLocale.latchTag(null)
        assertEquals(UiLanguage.Zh, UiLocale.language())
    }

    @Test
    fun explicitSystemTagStillFollowsTheSystem() {
        assertEquals(UiLocale.SYSTEM_TAG, UiLocale.effectiveTag(UiLocale.SYSTEM_TAG))
        UiLocale.latchTag(UiLocale.SYSTEM_TAG)
        assertEquals(UiLanguage.System, UiLocale.language())
    }
}
