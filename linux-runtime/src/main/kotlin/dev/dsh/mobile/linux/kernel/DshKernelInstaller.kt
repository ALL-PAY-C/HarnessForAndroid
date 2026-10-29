package dev.dsh.mobile.linux.kernel

import dev.dsh.mobile.linux.ElfHeader
import dev.dsh.mobile.linux.LinuxAbis
import dev.dsh.mobile.linux.LinuxEnvironment
import dev.dsh.mobile.linux.LinuxResult
import dev.dsh.mobile.linux.LinuxTar
import java.io.File
import java.io.InputStream

/** 静态 Node 运行时：官方 musl 构建解到 /root/node22。 */
data class NodeRuntime(
    val version: String,
    val sha256: String,
    val dir: File,
) {
    val bin: File get() = File(dir, "bin/node")
    val guestDir: String get() = DshKernelInstaller.GUEST_NODE_DIR
    val guestBin: String get() = DshKernel.NODE_BIN
}

/** 已安装的内核：版本 + 校验和 + 宿主目录 + 相对入口（+ 配套 Node 运行时）。 */
data class KernelInstallation(
    val version: String,
    val sha256: String,
    val dir: File,
    val entry: String,
    val node: NodeRuntime? = null,
) {
    val hostEntry: File get() = File(dir, entry)
    val guestDir: String get() = DshKernelInstaller.GUEST_DIR
    val guestEntry: String get() = DshKernelInstaller.GUEST_DIR + "/" + entry
}

/**
 * T6 安装器：assets/dsh-kernel 下的两个归档 → rootfs 的 /root/dsh（内核）与 /root/node22（Node）。
 *
 * 只碰 rootfs 里自己的东西：/root/dsh、/root/node22、两者的 staging/rollback 目录、/root/.dsh-kernel.json；
 * 用户 HOME 里其它文件一概不读不写不删。两个归档都先解到 staging（不动线上目录），
 * 全部校验通过后才依次 rename 切换，任一步失败按 undo 栈倒序还原。
 */
class DshKernelInstaller(
    val rootfs: File,
    private val open: (String) -> InputStream,
    private val sha256: (File) -> String = { LinuxEnvironment.sha256Of(it) },
    private val usableBytes: () -> Long = { rootfs.usableSpace },
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 标记文件写入缝：默认直写，测试可注入失败以验证「切换成功后回滚」。 */
    private val markerWriter: (File, String) -> Unit = { file, text -> file.writeText(text) },
    /** 是否同时安装 Node 运行时（内核单测可关掉，避免每次解 143MB）。 */
    private val withNode: Boolean = true,
    private val abi: () -> String? = { LinuxAbis.current() },
) {

    companion object {
        const val GUEST_DIR = "/root/dsh"
        const val GUEST_NODE_DIR = "/root/node22"

        /** 自己的标记文件（rootfs 相对路径）：记录当前生效的版本/校验和/入口与 Node 版本。 */
        const val MARKER = "root/.dsh-kernel.json"

        /** 归档与解压后目录会同时存在，留 3 倍 + 8MB 余量。 */
        const val RESERVE_BYTES = 8L * 1024 * 1024

        private val versionRe = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")
        private val shaRe = Regex("\"sha256\"\\s*:\\s*\"([^\"]*)\"")
        private val entryRe = Regex("\"entry\"\\s*:\\s*\"([^\"]*)\"")
        private val nodeVersionRe = Regex("\"nodeVersion\"\\s*:\\s*\"([^\"]*)\"")
        private val nodeShaRe = Regex("\"nodeSha256\"\\s*:\\s*\"([^\"]*)\"")
    }

    val dir: File get() = File(rootfs, "root/dsh")
    val nodeDir: File get() = File(rootfs, "root/node22")
    val marker: File get() = File(rootfs, MARKER)

    /** 标记文件 + 目录 + 入口三者都真实存在才算已安装。 */
    fun installed(): KernelInstallation? {
        val text = runCatching { marker.readText() }.getOrNull() ?: return null
        fun str(re: Regex) = re.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val version = str(versionRe) ?: return null
        val sha = str(shaRe) ?: return null
        val entry = str(entryRe) ?: DshKernelArtifacts.DEFAULT_ENTRY
        if (!dir.isDirectory) return null
        if (!File(dir, entry).isFile) return null
        val nodeVersion = str(nodeVersionRe)
        val nodeSha = str(nodeShaRe)
        val node = if (nodeVersion != null && nodeSha != null && NodeRuntime(nodeVersion, nodeSha, nodeDir).bin.isFile) {
            NodeRuntime(nodeVersion, nodeSha, nodeDir)
        } else {
            null
        }
        return KernelInstallation(version, sha, dir, entry, node)
    }

    /** 幂等：同版本同校验和（含 Node）且入口在，直接返回现有安装，不重复解包。 */
    fun install(source: KernelManifest): LinuxResult<KernelInstallation> {
        val currentAbi = abi() ?: return LinuxResult.Err("当前 ABI 不支持 Linux 内核")
        val kernel = DshKernelArtifacts.kernelArchive(source, currentAbi)
            ?: return LinuxResult.Err("当前 ABI($currentAbi) 缺少内核归档")
        val node = if (withNode && source.nodeVersion.isNotBlank()) {
            DshKernelArtifacts.nodeArchive(source, currentAbi)
                ?: return LinuxResult.Err("当前 ABI($currentAbi) 缺少 Node 运行时归档")
        } else null
        val manifest = source.copy(
            archive = kernel.archive, sha256 = kernel.sha256, bytes = kernel.bytes,
            nodeVersion = node?.nodeVersion?.ifBlank { source.nodeVersion } ?: source.nodeVersion,
            nodeArchive = node?.archive ?: source.nodeArchive, nodeSha256 = node?.sha256 ?: source.nodeSha256,
            nodeBytes = node?.bytes ?: source.nodeBytes, nodeStrip = node?.strip ?: source.nodeStrip,
        )
        if (manifest.version.isBlank() || manifest.entry.isBlank() || manifest.archive.isBlank() ||
            manifest.sha256.isBlank() || manifest.bytes <= 0L
        ) {
            return LinuxResult.Err("kernel manifest incomplete: " + manifest)
        }
        val wantsNode = withNode && manifest.nodeVersion.isNotBlank()
        if (wantsNode && (manifest.nodeArchive.isBlank() || manifest.nodeSha256.isBlank() || manifest.nodeBytes <= 0L)) {
            return LinuxResult.Err("kernel manifest node runtime incomplete: " + manifest)
        }
        installed()?.let {
            val kernelSame = it.version == manifest.version && it.sha256.equals(manifest.sha256, ignoreCase = true)
            val nodeSame = !wantsNode || (it.node != null &&
                it.node.version == manifest.nodeVersion &&
                it.node.sha256.equals(manifest.nodeSha256, ignoreCase = true) &&
                DshKernelArtifacts.nodeAbiError(currentAbi, ElfHeader.machineOf(it.node.bin)) == null)
            if (kernelSame && nodeSame) return LinuxResult.Ok(it)
        }

        val need = maxOf(manifest.bytes * 3 + if (wantsNode) manifest.nodeBytes * 3 else 0L, RESERVE_BYTES)
        val free = usableBytes()
        if (free < need) {
            return LinuxResult.Err("insufficient disk: need " + need + "B have " + free + "B")
        }

        val stamp = now()
        val kernelTmp = File(rootfs, "tmp/dsh-kernel-" + manifest.version + ".tgz")
        val kernelStaging = File(rootfs, "root/dsh.staging-" + manifest.version)
        val nodeTmp = File(rootfs, "tmp/dsh-node-" + manifest.nodeVersion + ".tgz")
        val nodeStaging = File(rootfs, "root/node22.staging-" + manifest.nodeVersion)
        val previousMarker = runCatching { marker.readText() }.getOrNull()
        val undo = ArrayList<() -> Unit>()
        try {
            // 1) 内核归档：落地 → 字节/校验和 → 解到 staging → 入口校验（全程不动线上目录）
            kernelTmp.parentFile?.mkdirs()
            kernelTmp.delete()
            open(DshKernelArtifacts.archivePath(manifest)).use { input ->
                kernelTmp.outputStream().use { input.copyTo(it) }
            }
            val kernelGot = kernelTmp.length()
            if (kernelGot != manifest.bytes) {
                return LinuxResult.Err("kernel bytes mismatch: expected " + manifest.bytes + " got " + kernelGot)
            }
            val kernelDigest = sha256(kernelTmp)
            if (!kernelDigest.equals(manifest.sha256, ignoreCase = true)) {
                return LinuxResult.Err("kernel sha256 mismatch: expected " + manifest.sha256 + " got " + kernelDigest)
            }
            kernelStaging.deleteRecursively()
            LinuxTar.extract(kernelTmp, kernelStaging)
            if (!File(kernelStaging, manifest.entry).isFile) {
                kernelStaging.deleteRecursively()
                return LinuxResult.Err("kernel entry missing after extract: " + manifest.entry)
            }

            // 2) Node 运行时归档：同样先 staging，再校验 bin/node 可执行
            if (wantsNode) {
                nodeTmp.parentFile?.mkdirs()
                nodeTmp.delete()
                open(DshKernelArtifacts.nodeArchivePath(manifest)).use { input ->
                    nodeTmp.outputStream().use { input.copyTo(it) }
                }
                val nodeGot = nodeTmp.length()
                if (nodeGot != manifest.nodeBytes) {
                    return LinuxResult.Err("node bytes mismatch: expected " + manifest.nodeBytes + " got " + nodeGot)
                }
                val nodeDigest = sha256(nodeTmp)
                if (!nodeDigest.equals(manifest.nodeSha256, ignoreCase = true)) {
                    return LinuxResult.Err("node sha256 mismatch: expected " + manifest.nodeSha256 + " got " + nodeDigest)
                }
                nodeStaging.deleteRecursively()
                nodeStaging.mkdirs()
                KernelTar.extract(nodeTmp, nodeStaging)
                // 归档顶层目录 node-vX-linux-x64-musl/ 剥掉，得到 bin/ lib/ share/
                val strip = manifest.nodeStrip
                val nodeRoot = if (strip.isNotEmpty() && File(nodeStaging, strip).isDirectory) {
                    File(nodeStaging, strip)
                } else {
                    nodeStaging
                }
                if (!File(nodeRoot, "bin/node").isFile) {
                    nodeStaging.deleteRecursively()
                    return LinuxResult.Err("node binary missing after extract: " + manifest.nodeStrip + "/bin/node")
                }
                DshKernelArtifacts.nodeAbiError(currentAbi, ElfHeader.machineOf(File(nodeRoot, "bin/node")))?.let {
                    kernelStaging.deleteRecursively()
                    nodeStaging.deleteRecursively()
                    return LinuxResult.Err(it)
                }
                if (nodeRoot != nodeStaging) {
                    // 把 strip 目录的内容上提一层：staging 里只留 bin/lib/share
                    val flat = File(rootfs, "root/node22.flatten-" + stamp)
                    flat.deleteRecursively()
                    if (!nodeRoot.renameTo(flat)) {
                        nodeStaging.deleteRecursively()
                        return LinuxResult.Err("activate failed: cannot flatten node staging")
                    }
                    nodeStaging.deleteRecursively()
                    nodeStaging.parentFile?.mkdirs()
                    if (!flat.renameTo(nodeStaging)) {
                        return LinuxResult.Err("activate failed: cannot rename node staging")
                    }
                }
                // Node 归档可能把 bin/node 标成 0644；运行时入口与配套工具必须显式可执行。
                for (name in listOf("node", "npm", "npx", "corepack")) {
                    val tool = File(nodeStaging, "bin/$name")
                    if (tool.isFile) tool.setExecutable(true, false)
                }
                val nodeBin = File(nodeStaging, "bin/node")
                if (!nodeBin.isFile || !nodeBin.canExecute()) {
                    nodeStaging.deleteRecursively()
                    return LinuxResult.Err("node binary missing or not executable after extract: " + manifest.nodeStrip + "/bin/node")
                }
            }

            // 3) 提交：Node 先切、内核后切，任一步失败按 undo 倒序还原。
            //    每个目录只挂一条 undo（先删新目录、再把 rollback 挪回原位），
            //    rollback 目录本身只在全部成功后才删 —— 否则回滚时旧版本已经被删掉。
            val rollbacks = ArrayList<File>()
            if (wantsNode) {
                val rollback = if (nodeDir.exists()) {
                    val dst = File(rootfs, "root/node22.rollback-" + stamp)
                    dst.deleteRecursively()
                    if (!nodeDir.renameTo(dst)) {
                        nodeStaging.deleteRecursively()
                        return LinuxResult.Err("activate failed: cannot move " + nodeDir.name)
                    }
                    rollbacks.add(dst)
                    dst
                } else {
                    null
                }
                if (!nodeStaging.renameTo(nodeDir)) {
                    nodeStaging.deleteRecursively()
                    runUndo(undo)
                    return LinuxResult.Err("activate failed: rename " + nodeStaging.name)
                }
                undo.add {
                    nodeDir.deleteRecursively()
                    rollback?.renameTo(nodeDir)
                }
            }

            val kernelRollback = if (dir.exists()) {
                val dst = File(rootfs, "root/dsh.rollback-" + stamp)
                dst.deleteRecursively()
                if (!dir.renameTo(dst)) {
                    kernelStaging.deleteRecursively()
                    runUndo(undo)
                    return LinuxResult.Err("activate failed: cannot move " + dir.name)
                }
                rollbacks.add(dst)
                dst
            } else {
                null
            }
            if (!kernelStaging.renameTo(dir)) {
                kernelStaging.deleteRecursively()
                runUndo(undo)
                return LinuxResult.Err("activate failed: rename " + kernelStaging.name)
            }
            undo.add {
                dir.deleteRecursively()
                kernelRollback?.renameTo(dir)
            }

            val node = if (wantsNode) NodeRuntime(manifest.nodeVersion, manifest.nodeSha256, nodeDir) else null
            val installation = KernelInstallation(manifest.version, manifest.sha256, dir, manifest.entry, node)
            writeMarker(installation)
            // 标记写成即提交成功：旧目录已无用途，undo 栈作废
            rollbacks.forEach { runCatching { it.deleteRecursively() } }
            return LinuxResult.Ok(installation)
        } catch (t: Throwable) {
            kernelStaging.deleteRecursively()
            nodeStaging.deleteRecursively()
            runUndo(undo)
            File(marker.parentFile, marker.name + ".tmp").delete()
            restoreMarker(previousMarker)
            return LinuxResult.Err("kernel install failed: " + (t.message ?: t.toString()), t)
        } finally {
            kernelTmp.delete()
            if (wantsNode) nodeTmp.delete()
        }
    }

    /** 卸载内核目录、Node 运行时与标记；HOME 里其它内容不动。 */
    fun remove(): Boolean {
        val gone = !dir.exists() || dir.deleteRecursively()
        nodeDir.deleteRecursively()
        marker.delete()
        return gone
    }

    private fun writeMarker(installation: KernelInstallation) {
        marker.parentFile?.mkdirs()
        val tmp = File(marker.parentFile, marker.name + ".tmp")
        val node = installation.node
        val text = buildString {
            append("{\"version\":\"").append(installation.version)
            append("\",\"sha256\":\"").append(installation.sha256)
            append("\",\"entry\":\"").append(installation.entry)
            if (node != null) {
                append("\",\"nodeVersion\":\"").append(node.version)
                append("\",\"nodeSha256\":\"").append(node.sha256)
            }
            append("\"}")
        }
        markerWriter(tmp, text)
        if (!tmp.renameTo(marker)) {
            marker.delete()
            tmp.renameTo(marker)
        }
    }

    /** 回滚时恢复旧 marker；不走 markerWriter —— 那条路刚刚才失败过。 */
    private fun restoreMarker(previous: String?) {
        if (previous == null) {
            marker.delete()
            return
        }
        runCatching { marker.writeText(previous) }
    }

    private fun runUndo(undo: List<() -> Unit>) {
        for (step in undo.asReversed()) runCatching { step() }
    }
}
