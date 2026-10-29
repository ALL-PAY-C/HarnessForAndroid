package dev.dsh.mobile.pluginhost

import java.io.File

/**
 * 预检：插件包在**执行前**就需要哪些「本端没有的东西」。
 *
 * 为什么要有这一层：真实桌面插件（cordis bundle）几乎都 `import 'node:fs/promises'`
 * 或 `import '@deepseek-ai/...'`。等到 ModuleLoader 撞上第一个 import 才报错，用户只看到
 * 「第一个缺的模块」，会以为补上就行；实际是整条 Node 宿主链路都不在本端。
 * 所以先扫一遍入口与包内相对 import，把**所有**外部模块一次性列出来。
 *
 * ponytail: 只做文本级扫描（行首 import / export from / require / 动态 import），
 * 不做 AST。漏判的后果是回退到 ModuleLoader 的逐条报错，不会误执行任何东西。
 */
internal object ExternalModules {

    /** 包内最多扫多少个文件；超出就停（插件入口一般 1-20 个 chunk）。 */
    private const val MAX_FILES = 40

    /** 报错里最多列几个模块，避免一屏刷满。 */
    private const val MAX_LISTED = 8

    private val SPEC = Regex(
        """(?:^|\n)\s*(?:import\s[^;\n]*?from\s*|import\s*|export\s[^;\n]*?from\s*|require\s*\(\s*|import\s*\(\s*)['"]([^'"]+)['"]""",
    )

    /** 外部模块（node 内置、npm 包）按出现顺序去重；相对 import 递归跟进。 */
    fun scan(dir: File, entry: File): List<String> {
        val seen = LinkedHashSet<String>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<File>()
        queue += entry
        while (queue.isNotEmpty() && visited.size < MAX_FILES) {
            val file = queue.removeFirst()
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (!visited.add(key)) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            SPEC.findAll(text).forEach { m ->
                val spec = m.groupValues[1]
                if (isRelative(spec)) {
                    resolveRelative(dir, file, spec)?.let { queue += it }
                } else {
                    seen += spec
                }
            }
        }
        return seen.toList()
    }

    fun describe(missing: List<String>): String {
        val quoted = missing.take(MAX_LISTED).joinToString("、") { "\"" + it + "\"" }
        val more = if (missing.size > MAX_LISTED) " 等 " + missing.size + " 个" else ""
        val nodeish = missing.any { it.startsWith("node:") || it.startsWith("@deepseek-ai/") }
        val head = "该插件依赖外部模块 " + quoted + more
        return if (nodeish) {
            head + "，本端不安装 npm 依赖；这类 Node 宿主插件会改走 NodeEngineHost 加载。"
        } else {
            head + "，本端不安装 npm 依赖。"
        }
    }

    private fun isRelative(spec: String): Boolean =
        spec.startsWith("./") || spec.startsWith("../") || spec == "." || spec == ".."

    /** 只解析包内的相对路径（补 .js / /index.js），越界一律不跟。 */
    private fun resolveRelative(dir: File, from: File, spec: String): File? {
        val base = runCatching { File(from.parentFile, spec).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        if (!base.path.startsWith(root.path)) return null
        val candidates = listOf(base, File(base.path + ".js"), File(base, "index.js"))
        return candidates.firstOrNull { it.isFile }
    }
}
