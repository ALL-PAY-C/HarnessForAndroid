package dev.dsh.mobile.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import dev.dsh.mobile.core.model.backup.RestoreMode

class RestoreRollbackTest {

    @Test
    fun `写库失败时整批回滚且活库不变`() {
        val source = FakeBackupDb()
        source.sessions["s1"] = FakeBackupDb.sessionRow("s1", "包内会话")
        source.messages["m1"] = FakeBackupDb.messageRow("m1", "s1", "第一条")
        source.messages["m2"] = FakeBackupDb.messageRow("m2", "s1", "第二条")

        val srcRoots = tempRoots("rollback-src")
        val pkg = File(srcRoots.filesDir, "rollback.hfabak")
        BackupWriter.Companion.newWriter(srcRoots, source).write(pkg, FULL_SELECTION)

        val target = FakeBackupDb()
        target.sessions["keep"] = FakeBackupDb.sessionRow("keep", "本地会话")
        target.messages["keep-m"] = FakeBackupDb.messageRow("keep-m", "keep", "本地消息")
        target.failTable = "messages"
        target.failAtRow = 2

        val dstRoots = tempRoots("rollback-dst")
        val result = RestoreEngine(dstRoots, target, PrefsSource.Empty, PrefsSink.Noop)
            .restore(pkg, RestoreMode.Merge)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("模拟写库失败"))
        assertEquals(1, target.sessions.size)
        assertEquals("本地会话", target.sessions["keep"]?.str("title"))
        assertEquals(1, target.messages.size)
        assertEquals("本地消息", target.messages["keep-m"]?.str("text"))
        assertTrue(dstRoots.restoreStagingDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `包内数据库版本高于本机时拒绝且不动活库`() {
        val source = FakeBackupDb(version = 13)
        source.sessions["s1"] = FakeBackupDb.sessionRow("s1", "未来的会话")

        val srcRoots = tempRoots("newer-src")
        val pkg = File(srcRoots.filesDir, "newer.hfabak")
        BackupWriter.Companion.newWriter(srcRoots, source).write(pkg, FULL_SELECTION)

        val target = FakeBackupDb(version = 12)
        target.sessions["keep"] = FakeBackupDb.sessionRow("keep", "本地会话")

        val result = RestoreEngine(tempRoots("newer-dst"), target, PrefsSource.Empty, PrefsSink.Noop)
            .restore(pkg, RestoreMode.Merge)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("请先升级 App"))
        assertEquals(1, target.sessions.size)
        assertEquals("本地会话", target.sessions["keep"]?.str("title"))
    }

    @Test
    fun `Replace 前快照失败即中止`() {
        val source = FakeBackupDb()
        source.sessions["s1"] = FakeBackupDb.sessionRow("s1", "包内会话")

        val srcRoots = tempRoots("replace-src")
        val pkg = File(srcRoots.filesDir, "replace.hfabak")
        BackupWriter.Companion.newWriter(srcRoots, source).write(pkg, FULL_SELECTION)

        val target = FakeBackupDb()
        target.sessions["keep"] = FakeBackupDb.sessionRow("keep", "本地会话")

        val result = RestoreEngine(
            roots = tempRoots("replace-dst"),
            db = target,
            prefs = PrefsSource.Empty,
            prefsSink = PrefsSink.Noop,
            snapshotBeforeReplace = { null },
        ).restore(pkg, RestoreMode.Replace)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("恢复前快照失败"))
        assertEquals(1, target.sessions.size)
        assertEquals("本地会话", target.sessions["keep"]?.str("title"))
    }
}
