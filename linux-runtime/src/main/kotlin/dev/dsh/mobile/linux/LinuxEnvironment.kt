package dev.dsh.mobile.linux

import java.io.File
import java.security.MessageDigest

data class LinuxInstallation(
    val version: String,
    val abi: String,
    val rootfs: File,
    val dir: File,
) {
    val id: String get() = "$version-$abi"
    internal fun repairExecutables(): Int = repairGuestExecutables(rootfs)
}

/** 旧安装可能丢执行位：完整扫描，单文件失败不打断其余修复。 */
internal fun repairGuestExecutables(rootfs: File): Int {
    val executableDirs = listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/libexec", "usr/lib/git-core", "root/node22/bin")
    var changed = 0
    var failed = 0
    rootfs.walkTopDown().onFail { _, _ -> failed++ }.forEach { file ->
        runCatching {
            if (file.isFile && !file.canExecute()) {
                val path = file.relativeTo(rootfs).invariantSeparatorsPath
                val executable = executableDirs.any { path.startsWith("$it/") } ||
                    file.inputStream().use { input ->
                        val first = input.read()
                        val second = input.read()
                        (first == 35 && second == 33) ||
                            (first == 127 && second == 69 && input.read() == 76 && input.read() == 70)
                    }
                if (executable) {
                    if (file.setExecutable(true, false) && file.canExecute()) changed++ else failed++
                }
            }
        }.onFailure { failed++ }
    }
    if (failed > 0) System.err.println("rootfs executable repair: changed=$changed failed=$failed")
    return changed
}

data class LinuxUsage(val bytes: Long, val files: Int)

/**
 * 版本化 rootfs 安装器。
 *
 * 目录布局（baseDir = filesDir/linux）：
 *   current                       —— 文本文件，内容是 "<version>-<abi>"
 *   versions/<version>-<abi>/rootfs/
 *   download/                     —— 只放临时下载文件
 *   tmp/                          —— PROOT_TMP_DIR
 *
 * 激活是原子的：先解到 versions/<id>.staging，成功后 rename 成正式目录，最后写 current。
 * 任何一步失败都不写 current，旧版本继续可用（不静默降级、不留半个环境）。
 */
class LinuxEnvironment(
    val baseDir: File,
    val abi: String,
    private val downloader: (LinuxManifest, File) -> Unit,
    private val sha256: (File) -> String = ::sha256Of,
    private val usableBytes: () -> Long = { baseDir.usableSpace },
) {

    private val versionsDir: File get() = File(baseDir, "versions")
    private val currentFile: File get() = File(baseDir, "current")
    val tmpDir: File get() = File(baseDir, "tmp")

    fun active(): LinuxInstallation? {
        val id = runCatching { currentFile.readText().trim() }.getOrNull().orEmpty()
        if (id.isEmpty()) return null
        val dir = File(versionsDir, id)
        val rootfs = File(dir, "rootfs")
        if (!rootfs.isDirectory) return null
        val dash = id.lastIndexOf('-')
        if (dash <= 0) return null
        return LinuxInstallation(id.substring(0, dash), id.substring(dash + 1), rootfs, dir)
    }

    fun prepare(manifest: LinuxManifest): LinuxResult<LinuxInstallation> {
        if (manifest.abi != abi) return LinuxResult.Err("abi mismatch: manifest=${manifest.abi} device=$abi")
        if (manifest.version.isBlank() || manifest.sha256.isBlank() || manifest.url.isBlank()) {
            return LinuxResult.Err("manifest incomplete: ${manifest}")
        }
        active()?.let {
            if (it.id == "${manifest.version}-${manifest.abi}") {
                repairGuestExecutables(it.rootfs)
                return LinuxResult.Ok(it)
            }
        }

        // 下载 + 解包 + 回滚副本三者同时存在，至少 3 倍；再留 16MB 给 proot 的临时文件。
        val need = maxOf(manifest.bytes * 3, RESERVE_BYTES)
        val free = usableBytes()
        if (free < need) return LinuxResult.Err("insufficient disk: need ${need}B have ${free}B")

        val id = "${manifest.version}-${manifest.abi}"
        val dlDir = File(baseDir, "download").apply { mkdirs() }
        val part = File(dlDir, "$id.tar.gz.part")
        val staging = File(versionsDir, "$id.staging")
        val finalDir = File(versionsDir, id)

        return try {
            part.delete()
            downloader(manifest, part)
            if (!part.isFile) return LinuxResult.Err("download failed: ${part.name} missing")
            if (part.length() != manifest.bytes) {
                return LinuxResult.Err("bytes mismatch: expected ${manifest.bytes} got ${part.length()}")
            }
            val digest = sha256(part)
            if (!digest.equals(manifest.sha256, ignoreCase = true)) {
                return LinuxResult.Err("sha256 mismatch: expected ${manifest.sha256} got $digest")
            }
            staging.deleteRecursively()
            staging.mkdirs()
            LinuxTar.extract(part, File(staging, "rootfs"))
            repairGuestExecutables(File(staging, "rootfs"))
            // proot 的 -b workDir:/workspace 与 -w /workspace 需要 guest 侧有这个挂载点。
            // 解包时顺手建好，避免第一次 exec 因 "can't chdir" 失败。
            File(staging, "rootfs/workspace").mkdirs()
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) return LinuxResult.Err("activate failed: rename $id")
            writeCurrent(id)
            LinuxResult.Ok(LinuxInstallation(manifest.version, manifest.abi, File(finalDir, "rootfs"), finalDir))
        } catch (t: Throwable) {
            staging.deleteRecursively()
            LinuxResult.Err(t.message ?: t.toString(), t)
        } finally {
            part.delete()
        }
    }

    /** 修复当前 rootfs；返回实际补回执行位的文件数。 */
    fun repairExecutables(): Int = active()?.repairExecutables() ?: 0

    /** 清除激活指针及当前版本，供上层重新安装。 */
    fun invalidateActive(): Boolean {
        val installation = active()
        val pointerDeleted = !currentFile.exists() || currentFile.delete()
        val versionDeleted = installation == null || !installation.dir.exists() || installation.dir.deleteRecursively()
        return pointerDeleted && versionDeleted
    }

    /** 删掉非当前版本的目录，返回删除个数。 */
    fun removeInactive(): Int {
        val keep = active()?.id ?: return 0
        val dirs = versionsDir.listFiles()?.filter { it.isDirectory } ?: return 0
        var removed = 0
        for (dir in dirs) {
            if (dir.name == keep || dir.name == "$keep.staging") continue
            if (dir.deleteRecursively()) removed++
        }
        return removed
    }

    fun usage(): LinuxUsage {
        var bytes = 0L
        var files = 0
        baseDir.walkTopDown().forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                files++
            }
        }
        return LinuxUsage(bytes, files)
    }

    private fun writeCurrent(id: String) {
        baseDir.mkdirs()
        val tmp = File(baseDir, "current.tmp")
        tmp.writeText(id)
        if (!tmp.renameTo(currentFile)) {
            currentFile.delete()
            tmp.renameTo(currentFile)
        }
    }

    companion object {
        /** 除制品本身外的最小余量：proot 临时目录 + 解包峰值。 */
        const val RESERVE_BYTES = 16L * 1024 * 1024

        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
