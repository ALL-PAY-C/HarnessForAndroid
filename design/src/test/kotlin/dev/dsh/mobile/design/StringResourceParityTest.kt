package dev.dsh.mobile.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the generated string catalogues: the English default (values/strings.xml) and the
 * Chinese overlay (values-zh/strings.xml) must stay key-for-key aligned, and every key must carry
 * the same printf placeholders in both languages. A key that only exists on one side silently falls
 * back to the default language at runtime, which is exactly the mixed-language bug this test blocks.
 */
class StringResourceParityTest {

    private fun resDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(dir, "src/main/res")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: error("src/main/res not found above " + System.getProperty("user.dir"))
        }
    }

    private fun parse(file: File): Map<String, String> {
        assertTrue("missing " + file.path, file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private val placeholder = Regex("%(\\d+\\\$)?[a-zA-Z]")

    private fun placeholders(value: String): List<String> =
        placeholder.findAll(value).map { it.value }.sorted().toList()

    @Test
    fun englishAndChineseCataloguesShareEveryKey() {
        val base = resDir()
        val en = parse(File(base, "values/strings.xml"))
        val zh = parse(File(base, "values-zh/strings.xml"))
        assertTrue("english catalogue is empty", en.isNotEmpty())
        assertEquals(
            "key sets differ — missing in values-zh: " + (en.keys - zh.keys) + " / missing in values: " + (zh.keys - en.keys),
            en.keys.sorted(),
            zh.keys.sorted(),
        )
    }

    @Test
    fun placeholdersMatchAcrossLanguages() {
        val base = resDir()
        val en = parse(File(base, "values/strings.xml"))
        val zh = parse(File(base, "values-zh/strings.xml"))
        val mismatched = en.keys.filter { placeholders(en[it].orEmpty()) != placeholders(zh[it].orEmpty()) }
            .map { it + " en=" + placeholders(en[it].orEmpty()) + " zh=" + placeholders(zh[it].orEmpty()) }
        assertEquals("placeholder mismatch: " + mismatched, emptyList<String>(), mismatched)
    }

    @Test
    fun noBlankValues() {
        val base = resDir()
        val blank = (parse(File(base, "values/strings.xml")).filterValues { it.isBlank() }.keys +
            parse(File(base, "values-zh/strings.xml")).filterValues { it.isBlank() }.keys)
        assertEquals("blank values: " + blank, emptySet<String>(), blank)
    }
}
