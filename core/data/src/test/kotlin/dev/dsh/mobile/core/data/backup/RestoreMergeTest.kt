package dev.dsh.mobile.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import dev.dsh.mobile.core.model.backup.RestoreMode

class RestoreMergeTest {

    private fun writePackage(tag: String, source: FakeBackupDb): Pair<BackupRoots, File> {
        val roots = tempRoots(tag)
        val pkg = File(roots.filesDir, tag + ".hfabak")
        BackupWriter.Companion.newWriter(roots, source).write(pkg, FULL_SELECTION)
        return roots to pkg
    }

    @Test
    fun `同 id 覆盖、异 id 新增、同名不同 id 加导入后缀`() {
        val source = FakeBackupDb()
        source.sessions["s1"] = FakeBackupDb.sessionRow("s1", "覆盖后的标题")
        source.messages["m1"] = FakeBackupDb.messageRow("m1", "s1", "新正文")
        source.sessions["s3"] = FakeBackupDb.sessionRow("s3", "本地已有标题")
        source.messages["m3"] = FakeBackupDb.messageRow("m3", "s3", "第三条")
        val (_, pkg) = writePackage("merge", source)

        val target = FakeBackupDb()
        target.sessions["s1"] = FakeBackupDb.sessionRow("s1", "旧标题")
        target.messages["m1"] = FakeBackupDb.messageRow("m1", "s1", "旧正文")
        target.sessions["s2"] = FakeBackupDb.sessionRow("s2", "本地已有标题")
        target.messages["s2m"] = FakeBackupDb.messageRow("s2m", "s2", "本地消息")

        RestoreEngine(tempRoots("merge-dst"), target, PrefsSource.Empty, PrefsSink.Noop)
            .restore(pkg, RestoreMode.Merge)
            .getOrThrow()

        assertEquals("覆盖后的标题", target.sessions["s1"]?.str("title"))
        assertEquals("新正文", target.messages["m1"]?.str("text"))
        assertEquals("本地已有标题（导入）", target.sessions["s3"]?.str("title"))
        assertEquals("本地已有标题", target.sessions["s2"]?.str("title"))
        assertEquals(3, target.sessions.size)
        assertEquals("本地消息", target.messages["s2m"]?.str("text"))
        assertTrue(target.warmCleared.contains("s1"))
        assertTrue(target.warmCleared.contains("s3"))
    }

    @Test
    fun `设置合并覆盖同名键但保留设备键`() {
        val source = FakeBackupDb()
        val srcRoots = tempRoots("merge-prefs-src")
        srcRoots.writeDatastore(
            "dsh_settings",
            mapOf("default_cwd" to PbValue.Str("/other-phone"), "theme" to PbValue.Str("dark")),
        )
        val pkg = File(srcRoots.filesDir, "prefs.hfabak")
        BackupWriter.Companion.newWriter(srcRoots, source).write(pkg, FULL_SELECTION)

        val dst = tempRoots("merge-prefs-dst")
        dst.writeDatastore(
            "dsh_settings",
            mapOf("default_cwd" to PbValue.Str("/local"), "theme" to PbValue.Str("light")),
        )
        RestoreEngine(dst, FakeBackupDb(), PrefsSource.Empty, PrefsSink.Noop)
            .restore(pkg, RestoreMode.Merge)
            .getOrThrow()

        val merged = dst.readDatastore("dsh_settings")
        assertEquals(PbValue.Str("/local"), merged["default_cwd"])
        assertEquals(PbValue.Str("dark"), merged["theme"])
        assertEquals(2, merged.size)
    }

    @Test
    fun `SharedPreferences 合并写回且不整文件清空`() {
        val source = FakeBackupDb()
        val srcRoots = tempRoots("merge-sp-src")
        val srcPrefs = object : PrefsSource {
            override fun read(name: String): Map<String, Any?> = if (name == "dsh.plugins") {
                mapOf("flags" to true, "count" to 3)
            } else {
                emptyMap()
            }
        }
        val pkg = File(srcRoots.filesDir, "sp.hfabak")
        BackupWriter.Companion.newWriter(srcRoots, source, srcPrefs).write(pkg, FULL_SELECTION)

        val sink = RecordingSink()
        RestoreEngine(tempRoots("merge-sp-dst"), FakeBackupDb(), PrefsSource.Empty, sink)
            .restore(pkg, RestoreMode.Merge)
            .getOrThrow()

        val applied = sink.applied["dsh.plugins"]
        assertEquals(false, applied?.second)
        assertEquals(true, applied?.first?.get("flags"))
        assertEquals(3, applied?.first?.get("count"))
    }

    private class RecordingSink : PrefsSink {
        val applied = LinkedHashMap<String, Pair<Map<String, Any?>, Boolean>>()

        override fun apply(name: String, values: Map<String, Any?>, replace: Boolean) {
            applied[name] = values to replace
        }
    }
}
