package dev.dsh.mobile.core.data.preset

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import dev.dsh.mobile.core.common.RuntimeMirrors
import dev.dsh.mobile.core.common.archive.ZipExtract
import dev.dsh.mobile.core.common.download.DownloadCache
import dev.dsh.mobile.core.common.download.Downloads
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * @param cacheDir 预设包的下载缓存目录；null 时不缓存（[importPreset] 的调用方多数没有 Context）。
 */
class AgentPresetImporter(private val cacheDir: File? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    fun importPreset(input: String): AgentPreset {
        val value = input.trim()
        require(value.isNotEmpty()) { "请输入 GitHub 链接、ZIP 路径或 JSON 预设" }
        return try {
            if (value.startsWith("{")) parseJson(value, "JSON 文本")
            else {
                val file = File(value.removePrefix("file://"))
                val bytes = if (file.isFile) file.inputStream().use { limited(it) }
                    else download(value)
                if (bytes.toString(Charsets.UTF_8).trimStart().startsWith("{"))
                    parseJson(bytes.toString(Charsets.UTF_8), value)
                else importZip(bytes, value)
            }
        } catch (e: IllegalArgumentException) { throw e }
        catch (e: Exception) { throw IllegalArgumentException("预设导入失败：" + (e.message ?: "读取错误"), e) }
    }

    private fun parseJson(text: String, source: String): AgentPreset {
        require(text.toByteArray().size <= MAX_BYTES) { "预设超过 20MB 限制" }
        val obj = try { json.parseToJsonElement(text).jsonObject }
            catch (e: Exception) { throw IllegalArgumentException("预设 JSON 格式错误", e) }
        fun field(key: String) = obj[key]?.jsonPrimitive?.content.orEmpty()
        return validated(AgentPreset(field("id"), field("name"), field("description"), field("prefix"), field("suffix"), source, System.currentTimeMillis()))
    }

    private fun download(input: String): ByteArray {
        val repo = Regex("^(?:https://github[.]com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:[.]git)?/?$").matchEntire(input)
        val url = if (repo != null) "https://github.com/" + repo.groupValues[1] + "/" + repo.groupValues[2] + "/archive/HEAD.zip" else input
        require(runCatching { URI(url).scheme in listOf("http", "https") }.getOrDefault(false)) { "请输入有效的 GitHub 仓库、HTTP 链接或本地 ZIP 路径" }
        // 目标文件只是下载落点（Android 上 java.io.tmpdir 就是应用 cacheDir），读完立刻删。
        val staging = File.createTempFile("dsh-preset-", ".zip")
        try {
            Downloads.downloadBlocking(
                urls = RuntimeMirrors.downloadCandidates(url),
                target = staging,
                cache = cacheDir?.let { DownloadCache(File(it, "dl-cache")) },
                maxBytes = MAX_BYTES.toLong(),
                label = "预设 " + url,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 30_000,
            )
            return staging.inputStream().use { limited(it) }
        } finally {
            staging.delete()
        }
    }

    fun importZip(bytes: ByteArray, source: String = "ZIP"): AgentPreset {
        require(bytes.size <= MAX_BYTES) { "预设超过 20MB 限制" }
        require(bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()) { "ZIP 文件损坏或格式无效" }
        val files = mutableMapOf<String, String>()
        val options = ZipExtract.Options(
            maxEntries = MAX_ENTRIES,
            maxEntryBytes = MAX_BYTES.toLong(),
            maxTotalBytes = MAX_BYTES.toLong(),
        )
        try {
            ZipExtract.forEachEntry(bytes.inputStream(), options) { name, isDirectory, data ->
                val path = ZipExtract.cleanName(name) ?: throw IllegalArgumentException("ZIP 路径穿越：$name")
                if (!isDirectory) {
                    // 非文本条目也要读：总解压量上限得把它们算进去，否则塞一堆 png 就能绕过。
                    val body = data.readBytes()
                    if (path.endsWith(".json") || path.endsWith(".yml") || path.endsWith(".yaml")) {
                        require(!files.containsKey(path)) { "ZIP 含重复文件：$path" }
                        files[path] = body.toString(Charsets.UTF_8)
                    }
                }
            }
        } catch (e: IllegalArgumentException) { throw e }
        catch (e: ZipExtract.ZipExtractException) { throw IllegalArgumentException(zipFailure(e), e) }
        catch (e: Exception) { throw IllegalArgumentException("ZIP 文件损坏：" + e.message, e) }
        val packages = files.keys.filter { it.substringAfterLast('/') == "package.json" }.sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))
        for (pkg in packages) {
            val patch = runCatching { json.parseToJsonElement(files.getValue(pkg)).jsonObject["dsh"]?.jsonObject?.get("bundle")?.jsonObject?.get("patch")?.jsonPrimitive?.content }.getOrNull() ?: continue
            val relative = patch.removePrefix("./")
            validatePath(relative)
            val path = pkg.substringBeforeLast('/', "").let { if (it.isEmpty()) relative else "$it/$relative" }
            val yaml = files[path] ?: throw IllegalArgumentException("预设包缺少声明的补丁文件：$relative")
            return parseYaml(yaml, source)
        }
        val yaml = files.keys.filter { it.substringAfterLast('/') == "cordis.patch.yml" }.minByOrNull { it.count { c -> c == '/' } }
        if (yaml != null) return parseYaml(files.getValue(yaml), source)
        val preset = files.keys.filter { it.endsWith(".json") && it.substringAfterLast('/') != "package.json" }.sortedBy { it.count { c -> c == '/' } }
        for (path in preset) runCatching { parseJson(files.getValue(path), source) }.getOrNull()?.let { return it }
        throw IllegalArgumentException("ZIP 中未找到 Agent 预设")
    }

    // ponytail: 只扫描 agent-preset -> config -> persona；不执行 YAML 标签、锚点或其它插件配置。
    fun parseYaml(text: String, source: String = "YAML"): AgentPreset {
        val lines = text.replace("\r\n", "\n").lines()
        fun indent(line: String) = line.takeWhile { it == ' ' }.length
        val name = lines.indexOfFirst { it.trimStart().startsWith("name:") && scalar(it.trimStart().substringAfter(':')) == "@deepseek-ai/dsh-agent-preset" }
        require(name >= 0) { "补丁中未找到 Agent 预设声明" }
        val itemIndent = indent(lines[name]) - 2
        val end = (name + 1 until lines.size).firstOrNull { lines[it].isNotBlank() && !lines[it].trimStart().startsWith('#') && indent(lines[it]) <= itemIndent } ?: lines.size
        val config = (name + 1 until end).firstOrNull { indent(lines[it]) == itemIndent + 2 && lines[it].trimStart().startsWith("config:") && scalar(lines[it].trimStart().substringAfter(':')).isEmpty() }
            ?: throw IllegalArgumentException("Agent 预设缺少 config")
        fun readField(key: String, start: Int, stop: Int, depth: Int): String {
            val at = (start until stop).firstOrNull { indent(lines[it]) == depth && lines[it].trimStart().startsWith("$key:") } ?: return ""
            val value = scalar(lines[at].trimStart().substringAfter(':'))
            if (value !in listOf("|", "|-", "|+")) return value
            val last = (at + 1 until stop).firstOrNull { lines[it].isNotBlank() && indent(lines[it]) <= depth } ?: stop
            val block = lines.subList(at + 1, last)
            val margin = block.filter { it.isNotBlank() }.minOfOrNull { indent(it) } ?: 0
            val content = block.joinToString("\n") { if (it.isBlank()) "" else it.drop(margin) }
            return when (value) { "|-" -> content.trimEnd('\n'); "|" -> content.trimEnd('\n') + "\n"; else -> content + "\n" }
        }
        val depth = itemIndent + 4
        val persona = (config + 1 until end).firstOrNull { (lines[it].trimStart().startsWith("- id:") && scalar(lines[it].trimStart().substringAfter(':')) == "persona") || (lines[it].trimStart().startsWith("name:") && scalar(lines[it].trimStart().substringAfter(':')) == "@deepseek-ai/dsh-persona") }
        val pDepth = persona?.let { if (lines[it].trimStart().startsWith('-')) indent(lines[it]) else indent(lines[it]) - 2 }
        val pEnd = if (persona != null && pDepth != null) (persona + 1 until end).firstOrNull { lines[it].isNotBlank() && indent(lines[it]) <= pDepth } ?: end else end
        val pConfig = if (persona != null && pDepth != null) (persona + 1 until pEnd).firstOrNull { indent(lines[it]) == pDepth + 2 && lines[it].trimStart().startsWith("config:") && scalar(lines[it].trimStart().substringAfter(':')).isEmpty() } else null
        return validated(AgentPreset(
            readField("id", config + 1, end, depth), readField("name", config + 1, end, depth), readField("description", config + 1, end, depth),
            if (pConfig == null) "" else readField("prefix", pConfig + 1, pEnd, pDepth!! + 4),
            if (pConfig == null) "" else readField("suffix", pConfig + 1, pEnd, pDepth!! + 4), source, System.currentTimeMillis(),
        ))
    }

    private fun scalar(raw: String): String {
        val s = raw.trim()
        if (s.startsWith('#')) return ""
        if (s.startsWith("'")) {
            val out = StringBuilder(); var i = 1
            while (i < s.length) {
                if (s[i] == '\'') { if (i + 1 < s.length && s[i + 1] == '\'') { out.append('\''); i += 2; continue }; return out.toString() }
                out.append(s[i++])
            }
            throw IllegalArgumentException("YAML 单引号未闭合")
        }
        if (s.startsWith('"')) {
            var escaped = false
            for (i in 1 until s.length) {
                if (s[i] == '"' && !escaped) return json.decodeFromString<String>(s.substring(0, i + 1))
                escaped = s[i] == '\\' && !escaped
            }
            throw IllegalArgumentException("YAML 双引号未闭合")
        }
        return s.replace(Regex("\\s+#.*$"), "").trim()
    }

    private fun validated(preset: AgentPreset): AgentPreset {
        require(preset.id.isNotBlank() && preset.name.isNotBlank()) { "预设缺少 id 或 name" }
        require(preset.id != "default") { "导入预设不可使用保留 id：default" }
        return preset
    }
    /** 解包失败 → 可读的业务错误；上限类错误沿用原有措辞（测试与 UI 都按这几个串认）。 */
    private fun zipFailure(e: ZipExtract.ZipExtractException): String = when (e) {
        is ZipExtract.ZipExtractException.IllegalEntry -> "ZIP 路径穿越：${e.entry}"
        is ZipExtract.ZipExtractException.TooManyEntries -> "ZIP 条目超过 ${e.limit} 限制"
        is ZipExtract.ZipExtractException.EntryTooLarge -> "预设超过 20MB 限制"
        is ZipExtract.ZipExtractException.TotalTooLarge -> "预设超过 20MB 限制"
        is ZipExtract.ZipExtractException.Corrupt -> "ZIP 文件损坏：" + (e.cause?.message ?: e.message)
    }

    private fun validatePath(path: String) {
        require(ZipExtract.cleanName(path) != null) { "ZIP 路径穿越：$path" }
    }
    private fun limited(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "预设超过 20MB 限制" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    companion object { const val MAX_BYTES = 20 * 1024 * 1024; const val MAX_ENTRIES = 2000 }
}
