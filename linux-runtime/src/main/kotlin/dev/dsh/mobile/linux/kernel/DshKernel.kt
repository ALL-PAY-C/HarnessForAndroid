package dev.dsh.mobile.linux.kernel

import dev.dsh.mobile.linux.ProotChannel
import dev.dsh.mobile.linux.LinuxAbis
import dev.dsh.mobile.toolbridge.ShellResult

/** assets/dsh-kernel/kernel-manifest.json 的内容。 */
data class KernelManifest(
    val version: String,
    val entry: String,
    val archive: String,
    val sha256: String,
    val bytes: Long,
    val files: Int = 0,
    val nodeEngine: String = "",
    val license: String = "",
    val source: String = "",
    /** 静态 Node 运行时（官方 musl 构建）：版本、归档、校验和、字节数、顶层目录名。 */
    val nodeVersion: String = "",
    val nodeArchive: String = "",
    val nodeSha256: String = "",
    val nodeBytes: Long = 0L,
    val nodeStrip: String = "",
    val archives: List<KernelArchive>? = null,
    val nodeArchives: List<KernelArchive>? = null,
)

data class KernelArchive(
    val abi: String,
    val archive: String,
    val sha256: String,
    val bytes: Long,
    val strip: String = "",
    val nodeVersion: String = "",
)

/**
 * 清单解析：和 LinuxArtifacts 同一套写法，不引 JSON 库。
 * 清单是脚本生成的扁平对象，正则足够，且多几个字段不影响。
 */
object DshKernelArtifacts {

    const val DIR = "dsh-kernel"
    const val FILE = "dsh-kernel/kernel-manifest.json"

    /** 官方 bin.js 的相对路径（与 kernel-manifest.json 的 entry 一致）。 */
    const val DEFAULT_ENTRY = "node_modules/@deepseek-ai/dsh/lib/bin.js"

    private val versionRe = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")
    private val entryRe = Regex("\"entry\"\\s*:\\s*\"([^\"]*)\"")
    private val archiveRe = Regex("\"archive\"\\s*:\\s*\"([^\"]*)\"")
    private val shaRe = Regex("\"sha256\"\\s*:\\s*\"([^\"]*)\"")
    private val bytesRe = Regex("\"bytes\"\\s*:\\s*(\\d+)")
    private val filesRe = Regex("\"files\"\\s*:\\s*(\\d+)")
    private val nodeRe = Regex("\"nodeEngine\"\\s*:\\s*\"([^\"]*)\"")
    private val licenseRe = Regex("\"license\"\\s*:\\s*\"([^\"]*)\"")
    private val sourceRe = Regex("\"source\"\\s*:\\s*\"([^\"]*)\"")
    private val nodeVersionRe = Regex("\"nodeVersion\"\\s*:\\s*\"([^\"]*)\"")
    private val nodeArchiveRe = Regex("\"nodeArchive\"\\s*:\\s*\"([^\"]*)\"")
    private val nodeShaRe = Regex("\"nodeSha256\"\\s*:\\s*\"([^\"]*)\"")
    private val nodeBytesRe = Regex("\"nodeBytes\"\\s*:\\s*(\\d+)")
    private val nodeStripRe = Regex("\"nodeStrip\"\\s*:\\s*\"([^\"]*)\"")

    private fun archiveArray(json: String, key: String): List<KernelArchive>? {
        val body = Regex("\"$key\"\\s*:\\s*\\[([\\s\\S]*?)\\]").find(json)?.groupValues?.get(1) ?: return null
        return Regex("\\{([^{}]*)\\}").findAll(body).mapNotNull { match ->
            val item = match.value
            fun str(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(item)?.groupValues?.get(1)
            val bytes = bytesRe.find(item)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            KernelArchive(
                str("abi") ?: return@mapNotNull null, str("archive") ?: return@mapNotNull null,
                str("sha256") ?: return@mapNotNull null, bytes, str("strip").orEmpty(), str("nodeVersion").orEmpty(),
            )
        }.toList()
    }

    fun parse(json: String): KernelManifest? {
        // 数组可能排在旧字段前，扁平字段只从顶层对象取，避免误读另一个 ABI。
        val top = Regex("\"(?:archives|nodeArchives)\"\\s*:\\s*\\[[\\s\\S]*?\\]").replace(json, "")
        fun str(re: Regex) = re.find(top)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        fun num(re: Regex) = re.find(top)?.groupValues?.get(1)?.toLongOrNull()
        val version = str(versionRe) ?: return null
        val archive = str(archiveRe) ?: return null
        val sha = str(shaRe) ?: return null
        return KernelManifest(
            version = version,
            entry = str(entryRe) ?: DEFAULT_ENTRY,
            archive = archive,
            sha256 = sha,
            bytes = num(bytesRe) ?: 0L,
            files = num(filesRe)?.toInt() ?: 0,
            nodeEngine = str(nodeRe).orEmpty(),
            license = str(licenseRe).orEmpty(),
            source = str(sourceRe).orEmpty(),
            nodeVersion = str(nodeVersionRe).orEmpty(),
            nodeArchive = str(nodeArchiveRe).orEmpty(),
            nodeSha256 = str(nodeShaRe).orEmpty(),
            nodeBytes = num(nodeBytesRe) ?: 0L,
            nodeStrip = str(nodeStripRe).orEmpty(),
            archives = archiveArray(json, "archives"),
            nodeArchives = archiveArray(json, "nodeArchives"),
        )
    }

    /** 新数组存在时严格按 ABI 命中；旧清单仅代表 x86_64。 */
    fun kernelArchive(manifest: KernelManifest, abi: String): KernelArchive? = when {
        LinuxAbis.map(listOf(abi)) == null -> null
        manifest.archives != null -> manifest.archives.firstOrNull { it.abi == abi }
        abi == LinuxAbis.X86_64 -> KernelArchive(abi, manifest.archive, manifest.sha256, manifest.bytes)
        else -> null
    }

    fun nodeArchive(manifest: KernelManifest, abi: String): KernelArchive? = when {
        LinuxAbis.map(listOf(abi)) == null -> null
        manifest.nodeArchives != null -> manifest.nodeArchives.firstOrNull { it.abi == abi }
        abi == LinuxAbis.X86_64 -> KernelArchive(abi, manifest.nodeArchive, manifest.nodeSha256, manifest.nodeBytes, manifest.nodeStrip, manifest.nodeVersion)
        else -> null
    }

    /** 纯校验函数；machine 来自共用 ElfHeader，失败早于任何目录激活。 */
    fun nodeAbiError(abi: String, machine: String?): String? {
        val expected = when (abi) { LinuxAbis.ARM64 -> "aarch64"; LinuxAbis.X86_64 -> "x86_64"; else -> null }
        return if (expected != null && machine == expected) null
        else "Node 归档 ABI 不匹配：设备 $abi，归档 " + (machine ?: "未知或无效 ELF")
    }

    /** assets 内的归档路径。 */
    fun archivePath(manifest: KernelManifest): String = DIR + "/" + manifest.archive

    /** assets 内静态 Node 运行时的归档路径。 */
    fun nodeArchivePath(manifest: KernelManifest): String = DIR + "/" + manifest.nodeArchive
}

/** 一次 node 调用的可测快照。 */
data class RunResult(val code: Int, val stdout: String, val stderr: String)

/** 内核握手结果：真跑通才算 ok，启动日志不算。 */
data class KernelHealth(
    val ok: Boolean,
    val version: String = "",
    val nodeVersion: String = "",
    val entry: String = "",
    val exitCode: Int = -1,
    val error: String? = null,
)

/**
 * T6：在 proot Alpine 内用系统 node 跑官方 DSH 内核（不 exec libnode.so）。
 *
 * 只负责拼命令与判定结果：HOME/PWD/PATH 显式写进命令行，不依赖 proot 的默认 env，
 * 也不把 files/runtime/home 从宿主 bind 进 guest。
 */
class DshKernel(
    private val channel: ProotChannel,
    val installation: KernelInstallation,
    val home: String = DEFAULT_HOME,
    val dir: String = DEFAULT_DIR,
    val timeoutMs: Long = 120_000,
    private val nodeMajorMin: Int = 22,
    private val repairExecutables: () -> Int = channel.installation::repairExecutables,
) {

    companion object {
        const val DEFAULT_HOME = "/root"
        const val DEFAULT_DIR = "/root/dsh"
        /** 静态 Node 运行时（官方 musl 构建）解到 HOME 下的固定目录，PATH 前置，不碰系统库。 */
        const val NODE_DIR = "/root/node22"
        const val NODE_BIN = "/root/node22/bin/node"

        /** 系统 PATH 保持原样，只在前面加上 node22/bin。 */
        const val SYSTEM_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        const val PATH = NODE_DIR + "/bin:" + SYSTEM_PATH

        /** bin.js --version 打出来的就是包版本号，例：0.2.1-rc.2 */
        val VERSION_RE = Regex("^(\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.\\-]+)?)$")
        val NODE_RE = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)")

        private fun firstLine(primary: String, fallback: String): String =
            (primary.trim().ifEmpty { fallback.trim() }).lineSequence().firstOrNull()?.take(300).orEmpty()

        /**
         * 双重判定：退出码为 0 **且** 输出里真有版本号。
         * node -v 必须 >= nodeMajorMin；内核 stdout 末行必须是裸版本号，
         * 只有启动日志/帮助文本一律判失败。
         */
        fun parseHealth(
            node: RunResult,
            kernel: RunResult,
            entry: String,
            nodeMajorMin: Int = 22,
        ): KernelHealth {
            if (node.code != 0) {
                return KernelHealth(
                    ok = false, entry = entry, exitCode = node.code,
                    error = "node -v failed (rc=" + node.code + "): " + firstLine(node.stderr, node.stdout),
                )
            }
            val nodeVersion = node.stdout.lineSequence().map { it.trim() }
                .firstOrNull { NODE_RE.containsMatchIn(it) }.orEmpty()
            if (nodeVersion.isEmpty()) {
                return KernelHealth(
                    ok = false, entry = entry, exitCode = node.code,
                    error = "unexpected node -v output: " + firstLine(node.stdout, node.stderr),
                )
            }
            val major = NODE_RE.find(nodeVersion)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (major < nodeMajorMin) {
                return KernelHealth(
                    ok = false, nodeVersion = nodeVersion, entry = entry, exitCode = node.code,
                    error = "node " + nodeMajorMin + "+ required, got " + nodeVersion,
                )
            }
            if (kernel.code != 0) {
                return KernelHealth(
                    ok = false, nodeVersion = nodeVersion, entry = entry, exitCode = kernel.code,
                    error = "kernel --version failed (rc=" + kernel.code + "): " + firstLine(kernel.stderr, kernel.stdout),
                )
            }
            val version = kernel.stdout.lineSequence().map { it.trim() }
                .lastOrNull { it.isNotEmpty() }?.takeIf { VERSION_RE.matches(it) }.orEmpty()
            if (version.isEmpty()) {
                return KernelHealth(
                    ok = false, nodeVersion = nodeVersion, entry = entry, exitCode = kernel.code,
                    error = "kernel --version printed no version: " + firstLine(kernel.stdout, kernel.stderr),
                )
            }
            return KernelHealth(
                ok = true, version = version, nodeVersion = nodeVersion,
                entry = entry, exitCode = kernel.code,
            )
        }
    }

    /** guest 内的入口路径：/root/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js */
    val guestEntry: String get() = dir + "/" + installation.entry

    private val prelude: String
        get() = "export HOME=" + home + " PWD=" + dir + " DSH_HOME=" + dir + " PATH=" + PATH + " LANG=C.UTF-8"

    /** 探测静态运行时：必须可执行，且 -v 输出交给 [parseHealth] 判 >=22。 */
    fun nodeVersionCommand(): String = "test -x " + NODE_BIN + " && " + NODE_BIN + " -v"

    /** cd 到 /root/dsh、显式 env、exec node 官方入口。nodeArgs 为空时只跑入口本身。 */
    fun command(nodeArgs: String = ""): String {
        val args = nodeArgs.trim()
        val tail = if (args.isEmpty()) "" else " " + args
        return "cd " + dir + " && " + prelude + " && exec " + NODE_BIN + " " + guestEntry + tail
    }

    /**
     * 握手：先确认 /root/node22/bin/node 在且 >=22（不走 apk —— Alpine 3.20 的 nodejs 只有 20，
     * 混 3.22 源会升级系统库把 node 打断），再跑内核 --version，交给 [parseHealth] 判定。
     */
    suspend fun health(): KernelHealth {
        val initial = probeHealth()
        if (initial.ok || listOf("Permission denied", "EACCES").none {
                initial.error.orEmpty().contains(it, ignoreCase = true)
            }) return initial
        runCatching { repairExecutables() }
        val retried = probeHealth()
        return if (retried.ok) retried else retried.copy(error = retried.error.orEmpty() +
            ";修复后仍不可执行，运行时目录可能落在不支持 exec 的位置（外部存储）或需要重装运行时")
    }

    private suspend fun probeHealth(): KernelHealth {
        val node = channel.exec(nodeVersionCommand(), timeoutMs)
        if (node.code != 0) {
            return KernelHealth(
                ok = false, entry = installation.entry, exitCode = node.code,
                error = "node runtime missing or broken: " + NODE_BIN +
                    " (rc=" + node.code + "): " + firstLine(node.stderr, node.stdout),
            )
        }
        val kernel = channel.exec(command("--version"), timeoutMs)
        return parseHealth(node.toRun(), kernel.toRun(), installation.entry, nodeMajorMin)
    }
}

private fun ShellResult.toRun() = RunResult(code, stdout, stderr)
