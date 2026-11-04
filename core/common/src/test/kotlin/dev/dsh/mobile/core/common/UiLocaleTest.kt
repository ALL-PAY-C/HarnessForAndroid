package dev.dsh.mobile.core.common

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
        UiLocale.latchTag(null)
        assertEquals(UiLanguage.System, UiLocale.language())
    }
}
