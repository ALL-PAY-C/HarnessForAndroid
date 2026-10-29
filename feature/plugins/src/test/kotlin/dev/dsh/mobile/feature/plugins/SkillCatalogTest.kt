package dev.dsh.mobile.feature.plugins

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 技能列表解析自检：真实回包字段、空表、坏 JSON。 */
class SkillCatalogTest {

    /** 引擎真实回包形状（两条 entries，字段全）。 */
    private val sample = """
        {"ok":true,"value":{"entries":[
          {"name":"hello-world","description":"打个招呼","globalEnabled":false,"projectEnabled":false,"effectiveEnabled":false,"updateSupported":false,"modelInvokable":true},
          {"name":"pdf-tools","description":"读 PDF","globalEnabled":true,"projectEnabled":true,"effectiveEnabled":true,"updateSupported":true,"modelInvokable":true}
        ],"projectRoots":[]}}
    """.trimIndent()

    @Test
    fun parsesEveryField() {
        val entries = parseCatalog(catalogOf(JSONObject(sample)))
        assertEquals(2, entries.size)
        assertEquals("hello-world", entries[0].name)
        assertEquals("打个招呼", entries[0].description)
        assertFalse(entries[0].globalEnabled)
        assertFalse(entries[0].projectEnabled)
        assertFalse(entries[0].effectiveEnabled)
        assertFalse(entries[0].updateSupported)
        assertTrue(entries[0].modelInvokable)

        assertEquals("pdf-tools", entries[1].name)
        assertTrue(entries[1].globalEnabled)
        assertTrue(entries[1].projectEnabled)
        assertTrue(entries[1].effectiveEnabled)
        assertTrue(entries[1].updateSupported)
    }

    @Test
    fun missingEntriesGivesEmptyList() {
        assertTrue(parseCatalog(JSONObject("""{"ok":true,"value":{"projectRoots":[]}}""")).isEmpty())
        assertTrue(parseCatalog(JSONObject("{}")).isEmpty())
    }

    @Test
    fun badJsonIsFailureNotCrash() {
        val r = parseCatalogResult("{oops")
        assertTrue(r.isFailure)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val entries = parseCatalog(JSONObject("""{"entries":[{"name":"a"}]}"""))
        assertEquals(1, entries.size)
        assertEquals("a", entries[0].name)
        assertEquals("", entries[0].description)
        assertFalse(entries[0].effectiveEnabled)
    }
}
