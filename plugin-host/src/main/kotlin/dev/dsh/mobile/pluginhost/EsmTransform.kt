package dev.dsh.mobile.pluginhost

/**
 * 把 ESM 源码降级成 Rhino 能直接编译的普通脚本（Rhino 1.7.15 不认 import/export）。
 *
 * 纯字符串变换，不引依赖。只认行首 / 语句级的常见写法：
 *  - import x from 'm'           -> const x = __interop(__require('m'))
 *  - import * as ns from 'm'     -> const ns = __require('m')
 *  - import { a, b as c } from   -> const { a, b: c } = __require('m')
 *  - import 'm'                  -> __require('m')
 *  - import d, { a } from 'm'    -> 上面两条的组合
 *  - export default <expr>       -> __exports.default = <expr>
 *  - export default function f   -> function f ... + 文件尾 __exports.default = f
 *  - export default class C      -> class C ... + 文件尾 __exports.default = C
 *  - export const/let/var NAME   -> 原声明 + 文件尾 __exports.NAME = NAME（支持逗号多声明）
 *  - export function/class NAME  -> 原声明 + 文件尾 __exports.NAME = NAME
 *  - export { a, b as c }        -> 文件尾 __exports.a = a; __exports.c = b
 *  - import.meta.url             -> __metaUrl（宿主注入本模块的 file:// URL）
 *  - import.meta.resolve(x)      -> __metaResolve(x)（宿主抛错，插件自带的 catch 会回落）
 *
 * ponytail: 不做 AST 级变换 —— 只按行首匹配。已知上限：
 *  1) 跨多行的 import 子句（import { 换行 a, 换行 b 换行 } from 'm'）不认，抛 UnsupportedException；
 *  2) 字符串/模板串里以 "import " 开头的一行会被误判（块注释里的行已跳过）；
 *  3) 声明类 export 的回填统一追加到文件尾，不是原地 —— 模块自己在执行中途读
 *     __exports 会读到空，循环依赖里同理；
 *  4) export default 的匿名多行函数/对象依赖 ASI 收尾，后面紧跟 "(" 开头的语句会串味。
 * 认不出的写法（export *、export { x } from 'y'、动态 import()、剩余 import.meta）
 * 一律抛 [UnsupportedException] 带文件与行号，绝不静默错改。
 * 真要精确语义就换 SWC/acorn 做真解析 —— 那是另一个量级的依赖，本端不引。
 */
object EsmTransform {

    /** 变换不了时的明确报错：文件、行号、原因都在里面。 */
    class UnsupportedException(
        val file: String,
        val line: Int,
        val detail: String,
    ) : Exception(file + ":" + line + " " + detail)

    fun transform(source: String, file: String = "<memory>"): String {
        // 幂等：没有任何 ESM 痕迹就原样返回，现有 14 项运行时测试走的都是这条路。
        if (!looksLikeEsm(source)) return source

        // 先换 .url：给的是本模块真实的 file:// 地址，插件拿它 fileURLToPath + dirname
        // 就能找到自己所在目录，换成空串会让后面那一步直接抛 TypeError。
        val text = source
            .replace("import.meta.url", "__metaUrl")
            .replace("import.meta.resolve", "__metaResolve")
        val lines = text.split("\n")
        val out = StringBuilder(text.length + 256)
        val tail = mutableListOf<String>()
        var inBlockComment = false

        for ((index, raw) in lines.withIndex()) {
            val lineNo = index + 1
            val trimmed = raw.trim()

            if (inBlockComment) {
                out.append(raw).append('\n')
                if (trimmed.contains("*/")) inBlockComment = false
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                out.append(raw).append('\n')
                continue
            }
            if (trimmed.startsWith("/*")) {
                out.append(raw).append('\n')
                if (!trimmed.contains("*/")) inBlockComment = true
                continue
            }
            if (RE_IMPORT_META.containsMatchIn(trimmed)) {
                throw UnsupportedException(file, lineNo, "import.meta 只支持 .url 和 .resolve，其余字段本端没有")
            }
            if (RE_DYNAMIC_IMPORT.containsMatchIn(trimmed)) {
                throw UnsupportedException(file, lineNo, "不支持动态 import()，模块必须静态引入")
            }

            val indent = raw.substring(0, raw.length - raw.trimStart().length)

            val imported = transformImport(trimmed, indent, file, lineNo)
            if (imported != null) {
                out.append(imported).append('\n')
                continue
            }
            val exported = transformExport(trimmed, indent, file, lineNo, tail)
            if (exported != null) {
                if (exported.isNotEmpty()) out.append(exported).append('\n')
                continue
            }
            out.append(raw).append('\n')
        }

        if (tail.isNotEmpty()) {
            out.append('\n').append("// ESM 导出回填（EsmTransform）\n")
            tail.forEach { out.append(it).append('\n') }
        }
        return out.toString()
    }

    // --------------------------------------------------------------- import

    private fun transformImport(line: String, indent: String, file: String, lineNo: Int): String? {
        if (!line.startsWith("import")) return null
        // importFoo / imported 之类不是 import 语句
        if (line.length > 6 && !" \t{*'\"".contains(line[6])) return null
        if (line.length == 6) return null

        // 打包产物（esbuild/terser）会把多条 import 挤在同一行，且 import 后面紧跟正文，
        // 所以按「行首逐条消费」处理：单条 import 的行走同一路径，吃完只剩空串，
        // 行为与按行整体匹配完全一致；一条都消费不掉才当作不认识的写法。
        val decls = StringBuilder()
        var cursor = line
        while (true) {
            val one = matchLeadingImport(cursor, file, lineNo) ?: break
            decls.append(one.first)
            cursor = cursor.substring(one.second)
            if (cursor.isBlank()) break
        }
        if (decls.isEmpty()) {
            throw UnsupportedException(file, lineNo, "认不出的 import 写法：" + line.take(160))
        }
        return indent + decls.toString() + cursor
    }

    /** 吃掉行首一条 import：返回（转换后的声明, 消费的字符数）；不是 import 返回 null。 */
    private fun matchLeadingImport(line: String, file: String, lineNo: Int): Pair<String, Int>? {
        RE_IMPORT_BARE_P.find(line)?.let { m ->
            return "__require('" + esc(m.groupValues[2]) + "');" to m.range.last + 1
        }
        RE_IMPORT_NS_P.find(line)?.let { m ->
            return "const " + m.groupValues[1] + " = __require('" + esc(m.groupValues[3]) + "');" to m.range.last + 1
        }
        RE_IMPORT_DEF_NS_P.find(line)?.let { m ->
            val spec = esc(m.groupValues[4])
            val ns = m.groupValues[2]
            return ("const " + ns + " = __require('" + spec + "'); " +
                "const " + m.groupValues[1] + " = __interop(" + ns + ");") to m.range.last + 1
        }
        RE_IMPORT_NAMED_P.find(line)?.let { m ->
            val binds = destructure(m.groupValues[1], file, lineNo)
            return "const " + binds + " = __require('" + esc(m.groupValues[3]) + "');" to m.range.last + 1
        }
        RE_IMPORT_DEF_NAMED_P.find(line)?.let { m ->
            val spec = esc(m.groupValues[4])
            val binds = destructure(m.groupValues[2], file, lineNo)
            return ("const " + m.groupValues[1] + " = __interop(__require('" + spec + "')); " +
                "const " + binds + " = __require('" + spec + "');") to m.range.last + 1
        }
        RE_IMPORT_DEFAULT_P.find(line)?.let { m ->
            return "const " + m.groupValues[1] + " = __interop(__require('" + esc(m.groupValues[3]) + "'));" to m.range.last + 1
        }
        return null
    }

    /** 花括号子句 -> 解构赋值：{ a, b as c } 变成 { a, b: c }。 */
    private fun destructure(clause: String, file: String, lineNo: Int): String {
        val parts = clause.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw UnsupportedException(file, lineNo, "import { } 里没有绑定名")
        val rendered = parts.joinToString(", ") { part ->
            val renamed = RE_AS.matchEntire(part)
            when {
                renamed != null -> renamed.groupValues[1] + ": " + renamed.groupValues[2]
                RE_IDENT.matchEntire(part) != null -> part
                else -> throw UnsupportedException(file, lineNo, "认不出的 import 绑定：" + part)
            }
        }
        return "{ " + rendered + " }"
    }

    // --------------------------------------------------------------- export

    private fun transformExport(
        line: String,
        indent: String,
        file: String,
        lineNo: Int,
        tail: MutableList<String>,
    ): String? {
        if (!line.startsWith("export")) return null
        if (line.length > 6 && !" \t{*".contains(line[6])) return null
        if (line.length == 6) return null

        if (RE_EXPORT_STAR.containsMatchIn(line)) {
            throw UnsupportedException(file, lineNo, "不支持 export *，需要显式列出导出名")
        }
        if (RE_EXPORT_NAMED_FROM.containsMatchIn(line)) {
            throw UnsupportedException(file, lineNo, "不支持 export { … } from '…' 再导出")
        }

        RE_EXPORT_NAMED.matchEntire(line)?.let { m ->
            val parts = m.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) throw UnsupportedException(file, lineNo, "export { } 里没有导出名")
            parts.forEach { part ->
                val renamed = RE_AS.matchEntire(part)
                when {
                    renamed != null ->
                        tail += "__exports." + renamed.groupValues[2] + " = " + renamed.groupValues[1] + ";"
                    RE_IDENT.matchEntire(part) != null -> tail += "__exports." + part + " = " + part + ";"
                    else -> throw UnsupportedException(file, lineNo, "认不出的 export 名字：" + part)
                }
            }
            return ""
        }

        RE_EXPORT_DEFAULT.matchEntire(line)?.let { m ->
            val rest = m.groupValues[1].trim()
            // 具名函数 / 类：原样留声明，回填挪到文件尾 —— 函数体可能跨很多行。
            RE_FN_NAME.find(rest)?.groupValues?.get(1)?.let { name ->
                tail += "__exports.default = " + name + ";"
                return indent + rest
            }
            RE_CLASS_NAME.find(rest)?.groupValues?.get(1)?.let { name ->
                tail += "__exports.default = " + name + ";"
                return indent + rest
            }
            // 匿名函数 / 表达式：赋值表达式语句本身可以跨行，就地转。
            val stmt = if (rest.endsWith(";")) rest else rest + ";"
            return indent + "__exports.default = " + stmt
        }

        RE_EXPORT_DECL.matchEntire(line)?.let { m ->
            val head = m.groupValues[1]
            val rest = m.groupValues[2]
            val isVarList = head.startsWith("const") || head.startsWith("let") || head.startsWith("var")
            if (isVarList) {
                declaratorNames(rest, file, lineNo).forEach { tail += "__exports." + it + " = " + it + ";" }
            } else {
                val name = RE_IDENT.find(rest)?.groupValues?.get(1)
                    ?: throw UnsupportedException(file, lineNo, "export " + head.trim() + " 缺少名字")
                tail += "__exports." + name + " = " + name + ";"
            }
            return indent + head + rest
        }

        throw UnsupportedException(file, lineNo, "认不出的 export 写法：" + line)
    }

    /** A = 1, B = f(1, 2) -> [A, B]：按顶层逗号切声明子，取每个声明子的头标识符。 */
    private fun declaratorNames(rest: String, file: String, lineNo: Int): List<String> {
        val body = rest.trim().removeSuffix(";")
        val parts = mutableListOf<String>()
        var depth = 0
        var quote = '\u0000'
        var start = 0
        for (i in body.indices) {
            val c = body[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
                continue
            }
            when (c) {
                '\'', '"', '`' -> quote = c
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    parts += body.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += body.substring(start)
        return parts.map { part ->
            RE_IDENT.find(part.trim())?.groupValues?.get(1)
                ?: throw UnsupportedException(file, lineNo, "export 解构声明不受支持：" + part.trim())
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun esc(spec: String): String = spec.replace("\\", "\\\\").replace("'", "\\'")

    private fun looksLikeEsm(source: String): Boolean = RE_ESM_HINT.containsMatchIn(source)

    private val RE_ESM_HINT = Regex("(?m)^[ \\t]*(?:import|export)\\b|\\bimport\\s*\\(|\\bimport\\.meta\\b")

    private const val ID = "[A-Za-z_\\$][\\w\\$]*"

    /** 带捕获组的标识符：import 那几个正则要按固定组号取绑定名与模块名。 */
    private const val IDG = "([A-Za-z_\\$][\\w\\$]*)"
    private const val STR = "(['\"])([^'\"]+)"

    // 组号约定：1 起按出现顺序；STR 占两组（引号、模块名），反引用指向"引号"那一组。
    private val RE_IMPORT_BARE = Regex("^import\\s+" + STR + "\\1\\s*;?\\s*$")
    private val RE_IMPORT_NS = Regex("^import\\s+\\*\\s+as\\s+" + IDG + "\\s+from\\s+" + STR + "\\2\\s*;?\\s*$")
    private val RE_IMPORT_NAMED = Regex("^import\\s*\\{([^}]*)\\}\\s*from\\s+" + STR + "\\2\\s*;?\\s*$")
    private val RE_IMPORT_DEF_NAMED =
        Regex("^import\\s+" + IDG + "\\s*,\\s*\\{([^}]*)\\}\\s*from\\s+" + STR + "\\3\\s*;?\\s*$")
    private val RE_IMPORT_DEF_NS =
        Regex("^import\\s+" + IDG + "\\s*,\\s*\\*\\s+as\\s+" + IDG + "\\s+from\\s+" + STR + "\\3\\s*;?\\s*$")
    private val RE_IMPORT_DEFAULT = Regex("^import\\s+" + IDG + "\\s+from\\s+" + STR + "\\2\\s*;?\\s*$")

    // 前缀版：不锚行尾，供「一行多条 import」逐条消费（见 [matchLeadingImport]）。
    // 空格一律 \s*：压缩包把 "from 'x'" 写成 "from'x'"，多一个 + 就整条匹配不上。
    private val RE_IMPORT_BARE_P = Regex("^import\\s*" + STR + "\\1\\s*;?")
    private val RE_IMPORT_NS_P = Regex("^import\\s*\\*\\s*as\\s+" + IDG + "\\s*from\\s*" + STR + "\\2\\s*;?")
    private val RE_IMPORT_DEF_NS_P =
        Regex("^import\\s*" + IDG + "\\s*,\\s*\\*\\s*as\\s+" + IDG + "\\s*from\\s*" + STR + "\\3\\s*;?")
    private val RE_IMPORT_NAMED_P = Regex("^import\\s*\\{([^}]*)\\}\\s*from\\s*" + STR + "\\2\\s*;?")
    private val RE_IMPORT_DEF_NAMED_P =
        Regex("^import\\s*" + IDG + "\\s*,\\s*\\{([^}]*)\\}\\s*from\\s*" + STR + "\\3\\s*;?")
    private val RE_IMPORT_DEFAULT_P = Regex("^import\\s*" + IDG + "\\s*from\\s*" + STR + "\\2\\s*;?")

    private val RE_EXPORT_STAR = Regex("^export\\s+\\*")
    private val RE_EXPORT_NAMED_FROM = Regex("^export\\s*\\{[^}]*\\}\\s*from\\s+")
    private val RE_EXPORT_NAMED = Regex("^export\\s*\\{([^}]*)\\}\\s*;?\\s*$")
    private val RE_EXPORT_DEFAULT = Regex("^export\\s+default\\s+(.*)$")
    private val RE_EXPORT_DECL =
        Regex("^export\\s+((?:async\\s+)?function\\s*\\*?\\s*|class\\s+|const\\s+|let\\s+|var\\s+)([\\s\\S]*)$")

    private val RE_FN_NAME = Regex("^(?:async\\s+)?function\\s*\\*?\\s*(" + ID + ")")
    private val RE_CLASS_NAME = Regex("^class\\s+(" + ID + ")")
    private val RE_IDENT = Regex("^(" + ID + ")")
    private val RE_AS = Regex("^(" + ID + "|default)\\s+as\\s+(" + ID + ")$")

    private val RE_DYNAMIC_IMPORT = Regex("(^|[^\\w\\$.])import\\s*\\(")
    private val RE_IMPORT_META = Regex("\\bimport\\.meta\\b")
}
