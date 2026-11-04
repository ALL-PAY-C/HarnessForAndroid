package dev.dsh.mobile.core.data.backup

import java.io.File

/** 测试用的临时目录树：四个根各一个子目录，互不干扰。 */
internal fun tempRoots(tag: String): BackupRoots {
    val base = File(System.getProperty("java.io.tmpdir"), "hfa-" + tag + "-" + System.nanoTime())
    fun dir(name: String) = File(base, name).apply { mkdirs() }
    return BackupRoots(
        filesDir = dir("files"),
        dataDir = dir("data"),
        dbDir = dir("db"),
        noBackupDir = dir("nobackup"),
    )
}

internal fun BackupWriter.Companion.newWriter(
    roots: BackupRoots,
    db: BackupDb,
    prefs: PrefsSource = PrefsSource.Empty,
): BackupWriter = BackupWriter(
    roots = roots,
    db = db,
    prefs = prefs,
    app = AppInfo(7, "1.2.3"),
    device = DeviceInfo("test", 34, "x86_64"),
    clock = { 1_700_000_000_000L },
)

internal val FULL_SELECTION = BackupSelection(
    sessions = null,
    settings = true,
    plugins = emptySet(),
    secrets = false,
    attachments = true,
    workspace = false,
)

internal fun BackupRoots.writeDatastore(name: String, values: Map<String, PbValue>) {
    datastoreDir.mkdirs()
    File(datastoreDir, name + ".preferences_pb").writeBytes(PreferencesPb.encode(values))
}

internal fun BackupRoots.readDatastore(name: String): Map<String, PbValue> {
    val f = File(datastoreDir, name + ".preferences_pb")
    if (!f.isFile) return emptyMap()
    return PreferencesPb.decode(f.readBytes())
}
