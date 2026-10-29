package dev.dsh.mobile.pluginhost

import org.json.JSONObject
import java.io.File

/**
 * 依赖安装要触网（registry 元数据 + tarball）。把它抽成这条窄缝有两个用处：
 *  - 单测用假实现就能覆盖全部解析/边界规则，一次真网都不碰（断网、墙内慢都会让测试飘）；
 *  - 超时等网络参数留在实现侧，解析逻辑只关心「拿到 JSON 文本 / 拿到本地文件」。
 */
internal interface NpmRegistrySource {
    /** registry 元数据文本（JSON）。[url] 是 registry.npmjs.org 下的完整地址。 */
    fun fetchText(url: String): String

    /** 把 [url] 指向的 tarball 落成本地文件；[timeoutMs] 是单包下载的墙钟上限。 */
    fun download(url: String, timeoutMs: Int): File
}

/**
 * 依赖安装摘要，随 [NpmInstallResult] 回到调用方。
 *
 * 依赖装不上**不**判安装失败：npm 包不打包 node_modules 是常态，让包先落地（声明式插件
 * 照跑、有依赖的插件降级成「加载失败：缺 X」）比整包装不上更好；失败包名在这里带出去。
 */
data class DependencySummary(
    val installed: Int = 0,
    val skipped: Int = 0,
    val failed: List<String> = emptyList(),
    /** 撞上包数上限或时间预算而提前收敛：剩下的依赖根本没试过，不是「装失败」。 */
    val capped: Boolean = false,
) {
    fun text(): String = buildString {
        append("依赖 installed=").append(installed).append(" skipped=").append(skipped)
        if (failed.isNotEmpty()) append(" failed=").append(failed.joinToString("、"))
        if (capped) append("（已到上限，其余未尝试）")
    }
}

/**
 * npm 运行期依赖解析。
 *
 * 规则（刻意保守：宁可少装，也不要把安装流程卡死）：
 *  - 只读 package.json 的 dependencies —— dev/optional/peer 在真机运行期用不到；
 *  - first-wins 落到 <包根>/node_modules/<name>，已经存在同名目录就跳过，不做版本冲突求解
 *    （手机上既没有 lockfile 也没有求解器，为它引一套依赖不值当）；
 *  - 边界：最多 [maxPackages] 个包、递归 [maxDepth] 层、整批时间预算 [budgetMs]；
 *  - 单个包失败只记一条 warning 继续下一个，半成品目录当场删掉，不留半个坏包。
 */
internal class NpmDependencyResolver(
    private val source: NpmRegistrySource,
    /** 解包实现；测试注入假实现，连 tar/gz 样本都不用造。 */
    private val unpack: (tarball: File, dest: File) -> Unit = ::unpackNpmTarball,
    private val maxPackages: Int = MAX_PACKAGES,
    private val maxDepth: Int = MAX_DEPTH,
    private val budgetMs: Long = BUDGET_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var attempted = 0
    private var installed = 0
    private var skipped = 0
    private var capped = false
    private val failed = mutableListOf<String>()

    fun install(
        pluginDir: File,
        packageJson: JSONObject?,
        warnings: MutableList<String>,
    ): DependencySummary {
        walk(pluginDir, packageJson, depth = 0, deadline = clock() + budgetMs, warnings = warnings)
        return DependencySummary(installed, skipped, failed.toList(), capped)
    }

    private fun walk(
        dir: File,
        packageJson: JSONObject?,
        depth: Int,
        deadline: Long,
        warnings: MutableList<String>,
    ) {
        val deps = packageJson?.optJSONObject("dependencies") ?: return
        val names = deps.keys().asSequence().sorted().toList()
        for (name in names) {
            if (attempted >= maxPackages) {
                capped = true
                warnings += "依赖超过上限 " + maxPackages + " 个，其余跳过"
                return
            }
            if (clock() > deadline) {
                capped = true
                warnings += "依赖安装超出时间预算，其余跳过"
                return
            }
            val nodeModules = File(dir, "node_modules")
            val dest = File(nodeModules, name)
            if (!isSafePackageName(name)) {
                failed += name
                warnings += "依赖名非法：$name"
                continue
            }
            // first-wins：已装过（不管是哪个版本）就不再覆盖，避免同一批里互相踩。
            if (dest.exists()) {
                skipped++
                continue
            }
            attempted++
            val requested = normalizeNpmSpec(deps.optString(name))
            val tmp = File(nodeModules, ".dsh-dep-" + attempted + ".tmp")
            try {
                // 先拿整个 packument 再挑版本：registry 的 /<name>/<range> 路径只认精确版本和
                // dist-tag，把 ^7.8.5 这类范围直接拼进 URL 会 404。
                val resolved = resolvePackage(name, requested)
                val tarballUrl = resolved.second
                val tarball = source.download(tarballUrl, TIMEOUT_MS)
                tmp.deleteRecursively()
                tmp.mkdirs()
                unpack(tarball, tmp)
                dest.parentFile?.mkdirs()
                if (!tmp.renameTo(dest)) {
                    tmp.copyRecursively(dest, overwrite = true)
                    tmp.deleteRecursively()
                }
                installed++
                // 递归装它自己的依赖：深度到顶就停（第 4 层开始只可能是构建期工具）。
                if (depth + 1 < maxDepth) {
                    walk(dest, readPackageJson(dest), depth + 1, deadline, warnings)
                }
            } catch (t: Throwable) {
                tmp.deleteRecursively()
                failed += name
                warnings += "依赖 " + name + "（" + requested + "）安装失败：" + (t.message ?: t::class.java.simpleName)
            }
        }
        if (depth >= maxDepth) {
            warnings += "依赖递归已到 " + maxDepth + " 层，" + dir.name + " 的依赖不再深入"
        }
    }

    /**
     * 从 packument 里挑出版本并取出 tarball 地址：返回「版本 → tarball」。
     *
     * 只做一次网络请求（原来是「查 meta + 下 tarball」两次，且范围会 404）。
     */
    private fun resolvePackage(name: String, requested: String): Pair<String, String> {
        val packument = JSONObject(source.fetchText(npmPackumentUrl(name)))
        val versions = packument.optJSONObject("versions")
            ?: error("registry 没有返回 " + name + " 的版本表")
        val tags = packument.optJSONObject("dist-tags")
        val tagMap = tags?.keys()?.asSequence()?.associateWith { tags.optString(it) }.orEmpty()
        val picked = npmPickVersion(requested, versions.keys().asSequence().toList(), tagMap)
            ?: error("没有满足 " + requested + " 的版本")
        val tarball = versions.optJSONObject(picked)?.optJSONObject("dist")?.optString("tarball").orEmpty()
        if (tarball.isBlank()) error("registry 没有给 " + name + "@" + picked + " 的 tarball")
        return picked to tarball
    }

    private fun readPackageJson(dir: File): JSONObject? =
        runCatching { JSONObject(File(dir, "package.json").readText()) }.getOrNull()

    companion object {
        /** 最多尝试装多少个包：npm 插件的依赖树通常个位数，60 足够覆盖，又不至于把手机拖死。 */
        internal const val MAX_PACKAGES = 60

        /** 递归深度上限：插件 → 依赖 → 依赖的依赖，再深就几乎都是构建期工具了。 */
        internal const val MAX_DEPTH = 3

        /** 单包下载墙钟上限。 */
        internal const val TIMEOUT_MS = 45_000

        /** 整批依赖的时间预算：60 个包各自超时的最坏情况不能被无限放大。 */
        internal const val BUDGET_MS = 5 * 60_000L
    }
}

/** registry 元数据地址：scoped 包名（@scope/name）的 / 要转成 %2f，否则会被当成两个路径段。 */
internal fun npmMetaUrl(name: String, spec: String): String =
    "https://registry.npmjs.org/" + name.replace("/", "%2f") + "/" + spec

/** 整个包的元数据（packument）：所有版本 + dist-tags 都在这，挑版本用。 */
internal fun npmPackumentUrl(name: String): String =
    "https://registry.npmjs.org/" + name.replace("/", "%2f")

/**
 * 按 npm 的版本范围在 [versions] 里挑一个：dist-tag 优先，其次解析 ^ ~ 精确 >= 这类写法，
 * 一律跳过预发布版本（1.0.0-beta 之类，插件用不上还容易踩到 API 变动）。
 */
internal fun npmPickVersion(requested: String, versions: List<String>, tags: Map<String, String>): String? {
    val spec = requested.trim()
    if (spec.isEmpty() || spec == "*" || spec == "latest") {
        tags["latest"]?.let { return it }
        return versions.filter { isPlainVersion(it) }.maxWithOrNull(VERSION_ORDER)
    }
    tags[spec]?.let { return it }
    val comparators = expandRange(spec) ?: return null
    return versions
        .filter { v -> parseVersion(v)?.let { pv -> comparators.all { it.test(pv) } } == true }
        .maxWithOrNull(VERSION_ORDER)
}

/** 版本三元组；解析失败（含预发布、非数字段）返回 null，调用方据此跳过该版本。 */
internal fun parseVersion(raw: String): Triple<Int, Int, Int>? {
    if (raw.isEmpty() || raw.contains('-') || raw.contains('+')) return null
    val parts = raw.split('.')
    if (parts.size > 3) return null
    val nums = parts.map { it.toIntOrNull() ?: return null }
    return Triple(nums.getOrElse(0) { 0 }, nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
}

private fun isPlainVersion(raw: String): Boolean = parseVersion(raw) != null

private val VERSION_ORDER: Comparator<String> = Comparator { a, b ->
    val pa = parseVersion(a) ?: return@Comparator -1
    val pb = parseVersion(b) ?: return@Comparator 1
    compareValuesBy(pa, pb, { it.first }, { it.second }, { it.third })
}

/** 一条比较：op 是 < <= = >= > 之一。 */
internal class VersionBound(val op: String, val ref: Triple<Int, Int, Int>) {
    fun test(v: Triple<Int, Int, Int>): Boolean {
        val c = compareValuesBy(v, ref, { it.first }, { it.second }, { it.third })
        return when (op) {
            "<" -> c < 0
            "<=" -> c <= 0
            ">" -> c > 0
            ">=" -> c >= 0
            else -> c == 0
        }
    }
}

/**
 * 把一段范围写法摊成合取比较列表：^1.2.3 → >=1.2.3 且 <2.0.0；~1.2.3 → >=1.2.3 且 <1.3.0；
 * 1.2 → >=1.2.0 且 <1.3.0；认不出的写法返回 null（调用方当失败处理）。
 */
internal fun expandRange(spec: String): List<VersionBound>? {
    val token = spec.split('|').first().trim()
    if (token.isEmpty()) return null
    if (token.contains(' ') || token.contains("||")) return null
    val out = mutableListOf<VersionBound>()
    when {
        token.startsWith("^") -> {
            val base = parseVersion(token.drop(1).trim()) ?: return null
            out += VersionBound(">=", base)
            val upper = when {
                base.first > 0 -> Triple(base.first + 1, 0, 0)
                base.second > 0 -> Triple(0, base.second + 1, 0)
                else -> Triple(0, 0, base.third + 1)
            }
            out += VersionBound("<", upper)
        }
        token.startsWith("~") -> {
            val base = parseVersion(token.drop(1).trim()) ?: return null
            out += VersionBound(">=", base)
            out += VersionBound("<", Triple(base.first, base.second + 1, 0))
        }
        token.startsWith(">=") || token.startsWith("<=") || token.startsWith(">") || token.startsWith("<") -> {
            val op = when {
                token.startsWith(">=") -> ">="
                token.startsWith("<=") -> "<="
                token.startsWith(">") -> ">"
                else -> "<"
            }
            val base = parseVersion(token.drop(op.length).trim()) ?: return null
            out += VersionBound(op, base)
        }
        else -> {
            val parts = token.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.isEmpty()) return null
            val base = Triple(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
            if (parts.size >= 3) {
                out += VersionBound("=", base)
            } else if (parts.size == 2) {
                out += VersionBound(">=", base)
                out += VersionBound("<", Triple(base.first, base.second + 1, 0))
            } else {
                out += VersionBound(">=", base)
                out += VersionBound("<", Triple(base.first + 1, 0, 0))
            }
        }
    }
    return out
}

/** 包名白名单：挡住 ../ 与绝对路径，保证 node_modules 下只会长出一层正常的目录。 */
internal fun isSafePackageName(name: String): Boolean =
    SAFE_PACKAGE.matches(name) && !name.contains("..")

private val SAFE_PACKAGE = Regex("^(@[A-Za-z0-9][A-Za-z0-9._-]*/)?[A-Za-z0-9][A-Za-z0-9._-]*$")

/**
 * 把 package.json 里的版本串收敛成 registry 能吃的一段：
 * 空 → latest；semver 范围（">=1.0.0 <2.0.0"）只留第一段（registry 接受范围，但不吃空格）；
 * 带 / 或 .. 的直接退成 latest —— 让它穿进 URL 就等于允许换成别的路径。
 */
internal fun normalizeNpmSpec(raw: String): String {
    val head = raw.trim().split(WHITESPACE).firstOrNull().orEmpty()
    if (head.isEmpty()) return "latest"
    if (head.contains('/') || head.contains("..")) return "latest"
    return head
}

private val WHITESPACE = Regex("\\s+")

/**
 * 解 npm tarball 到 [dest]。
 *
 * npm 包的所有条目都在 package/ 这层下面；直接解到 node_modules/<name> 会长成
 * node_modules/x/package/index.js，Node 的解析器认不出来。这里先解到临时目录，
 * 再把那一层剥掉搬到 [dest]，保证 dest 里就是包根。
 */
internal fun unpackNpmTarball(tarball: File, dest: File) {
    val staging = File(dest.parentFile ?: dest, dest.name + ".tar")
    staging.deleteRecursively()
    staging.mkdirs()
    try {
        tarball.inputStream().use { TarGz.extract(it, staging) }
        val inner = File(staging, "package").takeIf { it.isDirectory } ?: staging
        dest.mkdirs()
        inner.listFiles().orEmpty().forEach { child ->
            val target = File(dest, child.name)
            if (target.exists()) target.deleteRecursively()
            if (!child.renameTo(target)) {
                child.copyRecursively(target, overwrite = true)
                child.deleteRecursively()
            }
        }
    } finally {
        staging.deleteRecursively()
    }
}
