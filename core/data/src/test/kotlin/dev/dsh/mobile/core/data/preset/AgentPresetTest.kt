package dev.dsh.mobile.core.data.preset

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentPresetTest {
    private val importer = AgentPresetImporter()
    private fun resource(name: String) = javaClass.getResource("/presets/$name")!!.readText()
    private fun archive(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() } }
    }.toByteArray()
    private fun rejects(message: String, block: () -> Unit) {
        try { block(); fail("Expected $message") } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(message)) }
    }
    @Test fun realWhaleBundlePreservesPersonaAndSubstitutesCwdOnlyAtComposition() {
        val preset = importer.importZip(archive("repo/package.json" to resource("package.json"), "repo/cordis.patch.yml" to resource("cordis.patch.yml")))
        assertEquals("whale-elite", preset.id)
        assertEquals("鲸英模式", preset.name)
        assertTrue(preset.prefix.contains("鲸英模式"))
        assertTrue(preset.suffix.contains("{{cwd}}"))
        assertTrue(AgentPresets.composeSystemPrompt(preset, "BASE", "/workspace").contains("工作目录：/workspace"))
        assertTrue(AgentPresets.composeSystemPrompt(preset, "BASE", "/workspace").contains("\n\nBASE\n\n"))
        assertFalse(preset.prefix.endsWith("\n"))
    }
    @Test fun importsJsonAndKeepsMetadata() {
        val preset = importer.importPreset("""{"id":"demo","name":"测试","description":"说明","prefix":"before","suffix":"{{cwd}}"}""")
        assertEquals("demo", preset.id); assertEquals("说明", preset.description)
        assertEquals("before\n\nBASE\n\n/work", AgentPresets.composeSystemPrompt(preset, "BASE", "/work"))
        assertTrue(preset.importedAt > 0)
    }
    @Test fun standardPromptIsUnchanged() {
        assertEquals(" base {{cwd}} ", AgentPresets.composeSystemPrompt(AgentPresets.standard, " base {{cwd}} ", "/work"))
    }
    @Test fun malformedAndMissingArchivesHaveReadableErrors() {
        rejects("ZIP") { importer.importZip("bad".toByteArray()) }
        rejects("未找到") { importer.importZip(archive("README.md" to "none")) }
    }
    @Test fun rejectsTraversalIncludingWindowsPaths() {
        listOf("../evil", "a/../../evil", "C:/evil", "/evil", "a\\..\\evil").forEach { path ->
            rejects("路径穿越") { importer.importZip(archive(path to "x")) }
        }
    }
    @Test fun entryAndExpandedByteLimitsAreEnforced() {
        rejects("2000") { importer.importZip(archive(*(0..2000).map { "$it.txt" to "" }.toTypedArray())) }
        rejects("20MB") { importer.importZip(archive("large.txt" to "x".repeat(AgentPresetImporter.MAX_BYTES + 1))) }
    }
    @Test fun shallowestBundleWins() {
        val pkg = resource("package.json"); val yaml = resource("cordis.patch.yml")
        assertEquals("whale-elite", importer.importZip(archive("deep/nested/package.json" to pkg, "deep/nested/cordis.patch.yml" to yaml.replace("id: whale-elite", "id: other"), "root/package.json" to pkg, "root/cordis.patch.yml" to yaml)).id)
    }
    @Test fun quotedFieldsAndInlineCommentsAreSupported() {
        val yaml = resource("cordis.patch.yml").replace("id: whale-elite", "id: 'whale-elite' # comment").replace("name: 鲸英模式", "name: \"鲸英模式\" # comment")
        assertEquals("鲸英模式", importer.parseYaml(yaml).name)
        assertEquals("whale-elite", importer.parseYaml(yaml).id)
    }
    @Test fun commentsOnConfigAndPersonaDoNotLosePrompt() {
        val yaml = resource("cordis.patch.yml").replace("config:", "config: # settings").replace("- id: persona", "- id: persona # prompt")
        val preset = importer.parseYaml(yaml)
        assertTrue(preset.prefix.contains("鲸英模式"))
        assertTrue(preset.suffix.contains("{{cwd}}"))
    }
    @Test fun localZipIsReadable() {
        val file = File.createTempFile("preset", ".zip")
        try { file.writeBytes(archive("preset.json" to """{"id":"local","name":"Local"}""")); assertEquals("local", importer.importPreset(file.absolutePath).id) }
        finally { file.delete() }
    }
    @Test fun storeRoundTripsAndProtectsStandard() = runBlocking {
        val file = File.createTempFile("presets", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
            val store = AgentPresetStore(dataStore)
            assertEquals(listOf(AgentPresets.standard), store.presets.first())
            val preset = AgentPreset("demo", "示例", prefix = "a\nb")
            store.upsert(preset); store.upsert(preset.copy(name = "更新"))
            assertEquals("更新", AgentPresetStore(dataStore).get("demo")!!.name)
            assertEquals(2, store.presets.first().size)
            try { store.remove("default"); fail("standard must be protected") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("标准")) }
            store.remove("demo"); assertNull(store.get("demo"))
        } finally { scope.cancel(); file.delete() }
    }
}
