package dev.dsh.mobile.feature.plugins

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 技能安装器的自检：输入解析、下载候选、解压限制、staging 定位。
 *
 * 全程不联网——下载一律注入假实现，zip 用临时文件现造。
 */
class SkillInstallerTest {

    private fun tempDir(prefix: String): File = kotlin.io.path.createTempDirectory(prefix).toFile()

    /** name -> 内容；内容为 null 表示目录条目。 */
    private fun zip(dir: File, entries: List<Pair<String, ByteArray?>>, name: String = "sample.zip"): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (entryName, body) ->
                out.putNextEntry(ZipEntry(entryName))
                if (body != null) out.write(body)
                out.closeEntry()
            }
        }
        return file
    }

    private val skillMd = "# Demo Skill\n\n做点事\n".toByteArray()
    private val manifest = "demo-skill/SKILL.md" to skillMd

    // ---- 输入解析 ----

    @Test
    fun parsesShortOwnerRepo() {
        assertEquals(
            SkillInstaller.Source.GitHub("obra", "superpowers", null),
            SkillInstaller.parse("obra/superpowers"),
        )
    }

    @Test
    fun parsesGitHubUrlWithTreeBranch() {
        assertEquals(
            SkillInstaller.Source.GitHub("obra", "superpowers", "dev"),
            SkillInstaller.parse("https://github.com/obra/superpowers/tree/dev"),
        )
    }

    @Test
    fun parsesPlainGitHubUrl() {
        assertEquals(
            SkillInstaller.Source.GitHub("obra", "superpowers", null),
            SkillInstaller.parse("https://github.com/obra/superpowers"),
        )
    }

    @Test
    fun parsesZipDirectLink() {
        assertEquals(
            SkillInstaller.Source.ZipUrl("https://example.com/dl/skills.zip"),
            SkillInstaller.parse("https://example.com/dl/skills.zip"),
        )
    }

    @Test
    fun rejectsGarbage() {
        assertNull(SkillInstaller.parse("随便写点什么"))
        assertNull(SkillInstaller.parse(""))
    }

    // ---- 下载候选 ----

    @Test
    fun downloadUrlsTryMainThenMaster() {
        assertEquals(
            listOf(
                "https://codeload.github.com/obra/superpowers/zip/refs/heads/main",
                "https://codeload.github.com/obra/superpowers/zip/refs/heads/master",
            ),
            SkillInstaller.downloadUrls(SkillInstaller.Source.GitHub("obra", "superpowers")),
        )
    }

    @Test
    fun downloadUrlsPreferUrlBranch() {
        assertEquals(
            listOf(
                "https://codeload.github.com/obra/superpowers/zip/refs/heads/dev",
                "https://codeload.github.com/obra/superpowers/zip/refs/heads/main",
                "https://codeload.github.com/obra/superpowers/zip/refs/heads/master",
            ),
            SkillInstaller.downloadUrls(SkillInstaller.Source.GitHub("obra", "superpowers", "dev")),
        )
    }

    @Test
    fun downloadUrlsPassZipLinkThrough() {
        assertEquals(
            listOf("https://example.com/dl/skills.zip"),
            SkillInstaller.downloadUrls(SkillInstaller.Source.ZipUrl("https://example.com/dl/skills.zip")),
        )
    }

    // ---- 解压 ----

    @Test
    fun extractsZipWithSkillManifest() {
        val work = tempDir("skill-zip")
        val archive = zip(work, listOf(manifest))
        val into = File(work, "stage")

        SkillInstaller.extract(archive, into)

        val skill = File(into, "demo-skill/SKILL.md")
        assertTrue("SKILL.md 应被解出来", skill.isFile)
        assertEquals(String(skillMd), skill.readText())
    }

    @Test
    fun rejectsPathTraversalEntry() {
        val work = tempDir("skill-zip")
        val archive = zip(work, listOf("../evil.txt" to "boom".toByteArray()))
        val into = File(work, "stage")

        try {
            SkillInstaller.extract(archive, into)
            fail("路径穿越条目必须被拒")
        } catch (e: SkillInstaller.SkillInstallException) {
            assertTrue("错误里应说明非法路径，实际 ${e.message}", (e.message ?: "").contains("非法路径"))
        }
        assertFalse("越界文件不能落盘", File(work, "evil.txt").exists())
    }

    @Test
    fun rejectsAbsolutePathEntry() {
        val work = tempDir("skill-zip")
        val archive = zip(work, listOf("/etc/evil.txt" to "boom".toByteArray()))
        val into = File(work, "stage")

        try {
            SkillInstaller.extract(archive, into)
            fail("绝对路径条目必须被拒")
        } catch (e: SkillInstaller.SkillInstallException) {
            assertTrue("错误里应说明非法路径，实际 ${e.message}", (e.message ?: "").contains("非法路径"))
        }
    }

    @Test
    fun rejectsTooManyEntries() {
        val work = tempDir("skill-zip")
        val entries = (1..5).map { "f$it.txt" to "x".toByteArray() }
        val archive = zip(work, entries)

        try {
            SkillInstaller.extract(archive, File(work, "stage"), SkillInstaller.Limits(maxEntries = 3))
            fail("条目数超限必须被拒")
        } catch (e: SkillInstaller.SkillInstallException) {
            assertTrue("错误里应说明条目上限，实际 ${e.message}", (e.message ?: "").contains("条目太多"))
        }
    }

    @Test
    fun rejectsOversizedSingleFile() {
        val work = tempDir("skill-zip")
        val archive = zip(work, listOf("big.bin" to ByteArray(4096)))

        try {
            SkillInstaller.extract(archive, File(work, "stage"), SkillInstaller.Limits(maxEntryBytes = 1024))
            fail("单文件超限必须被拒")
        } catch (e: SkillInstaller.SkillInstallException) {
            assertTrue("错误里应说明文件上限，实际 ${e.message}", (e.message ?: "").contains("单个文件太大"))
        }
    }

    @Test
    fun rejectsOversizedTotal() {
        val work = tempDir("skill-zip")
        val archive = zip(work, listOf("a.bin" to ByteArray(800), "b.bin" to ByteArray(800)))

        try {
            SkillInstaller.extract(archive, File(work, "stage"), SkillInstaller.Limits(maxTotalBytes = 1024))
            fail("解压总大小超限必须被拒")
        } catch (e: SkillInstaller.SkillInstallException) {
            assertTrue("错误里应说明总大小上限，实际 ${e.message}", (e.message ?: "").contains("总大小"))
        }
    }

    // ---- staging 定位 ----

    @Test
    fun stageRootUnwrapsSingleTopDirectory() {
        val work = tempDir("skill-stage")
        File(work, "repo-main").mkdirs()
        File(work, "repo-main/SKILL.md").writeText("# x")

        assertEquals(File(work, "repo-main").canonicalFile, SkillInstaller.stageRoot(work).canonicalFile)
    }

    @Test
    fun stageRootKeepsRootForMultipleTopDirs() {
        val work = tempDir("skill-stage")
        File(work, "a").mkdirs()
        File(work, "b").mkdirs()

        assertEquals(work.canonicalFile, SkillInstaller.stageRoot(work).canonicalFile)
    }

    @Test
    fun stageRootKeepsRootWhenManifestAtTop() {
        val work = tempDir("skill-stage")
        File(work, "SKILL.md").writeText("# x")
        File(work, "docs").mkdirs()

        assertEquals(work.canonicalFile, SkillInstaller.stageRoot(work).canonicalFile)
    }

    // ---- 端到端（假下载）----

    private fun fetcherFrom(archive: File): (String, File) -> Unit = { _, out ->
        archive.copyTo(out, overwrite = true)
    }

    @Test
    fun installStagesSkillFromDownloadedZip() {
        val cache = tempDir("skill-cache")
        val work = tempDir("skill-zip")
        val archive = zip(work, listOf(manifest))

        val root = SkillInstaller.install(cache, "obra/superpowers", fetcherFrom(archive))

        assertTrue("staging 里应有 SKILL.md，实际 ${root.absolutePath}", File(root, "SKILL.md").isFile)
        assertTrue("staging 必须落在 cacheDir 下", root.canonicalPath.startsWith(cache.canonicalPath))
        assertTrue("下载的临时包要删掉", cache.listFiles().orEmpty().none { it.name.endsWith(".zip") })
    }

    @Test
    fun installFailsWhenNoSkillManifest() {
        val cache = tempDir("skill-cache")
        val work = tempDir("skill-zip")
        val archive = zip(work, listOf("readme.md" to "hi".toByteArray()))

        try {
            SkillInstaller.install(cache, "obra/superpowers", fetcherFrom(archive))
            fail("没有 SKILL.md 必须报错")
        } catch (e: SkillInstaller.SkillInstallException) {
            assertTrue("错误里应提到 SKILL.md，实际 ${e.message}", (e.message ?: "").contains("SKILL.md"))
        }
    }

    @Test
    fun installSurfacesDownloadFailureAndCleansStage() {
        val cache = tempDir("skill-cache")

        try {
            SkillInstaller.install(cache, "obra/superpowers") { _, _ -> throw IOException("HTTP 404") }
            fail("下载失败必须报错")
        } catch (e: Exception) {
            assertTrue("错误里应带上真实原因，实际 ${e.message}", (e.message ?: "").contains("404"))
        }
        assertTrue(
            "失败后 staging 不能留在 cacheDir，实际 ${cache.listFiles()?.map { it.name }}",
            cache.listFiles().orEmpty().none { it.name.startsWith("skill-stage-") },
        )
    }
}
