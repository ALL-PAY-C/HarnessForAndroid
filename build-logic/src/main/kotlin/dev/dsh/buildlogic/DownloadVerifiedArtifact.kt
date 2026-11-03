package dev.dsh.buildlogic

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * 按 sha256 内容寻址下载一个构建期产物，两处 runtime 模块共用。
 *
 * 来源顺序：dest 已有且校验通过 > 预置文件 > 预置目录 > 缓存目录（`<sha256>` 或产物同名文件）
 * > 网络（每个源直连优先，再依次套 [mirrorPrefixes]）。下载落 `.part` 临时文件，sha256
 * 校验通过才原子改名；不符则删除并换下一个源。`--offline` 且本地无副本时给出可读的预置指引。
 */
abstract class DownloadVerifiedArtifact : DefaultTask() {

    /** 上游原始 URL，按优先级排列。 */
    @get:Input
    abstract val sources: ListProperty<String>

    /** 镜像前缀（单一真源见 [Mirrors]），每个前缀都会拼在每个 sources URL 前面兜底。 */
    @get:Input
    abstract val mirrorPrefixes: ListProperty<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    /** 期望字节数，<= 0 表示不校验（sha256 已足够）。 */
    @get:Input
    abstract val expectedBytes: Property<Long>

    /** 每个 URL 的最大尝试次数（含首次）。 */
    @get:Input
    abstract val retries: Property<Int>

    @get:Input
    abstract val connectTimeoutMs: Property<Long>

    @get:Input
    abstract val readTimeoutMs: Property<Long>

    /** 内容寻址缓存目录：缓存文件名是小写 `<sha256>`。 */
    @get:Internal
    abstract val cacheDir: DirectoryProperty

    /** 预置目录（离线 / CI），先找 `<dir>/<preloadRelativePath>`，再找 `<dir>/<dest 文件名>`。 */
    @get:Internal
    abstract val preloadDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val preloadRelativePath: Property<String>

    /** 预置文件（离线 / CI 明确指定点对点路径）。 */
    @get:Internal
    abstract val preloadFile: RegularFileProperty

    /** 产物最终落点；不设则只落内容寻址缓存。 */
    @get:OutputFile
    @get:Optional
    abstract val dest: RegularFileProperty

    /** offline 且无副本时附在错误信息里的预置指引（各模块自述自己的 -P 参数）。 */
    @get:Input
    @get:Optional
    abstract val offlineHint: Property<String>

    /** 内容寻址缓存文件（任务真正的产物，也是解包 / 校验任务的输入）。 */
    @get:OutputFile
    val cachedArtifact: Provider<RegularFile>
        get() = cacheDir.file(expectedSha256.map { it.trim().lowercase() })

    /** 配置期定格，避免执行期访问 project（configuration cache 友好）。 */
    @get:Internal
    val offline: Boolean = project.gradle.startParameter.isOffline

    init {
        expectedBytes.convention(0L)
        retries.convention(2)
        connectTimeoutMs.convention(30_000L)
        readTimeoutMs.convention(180_000L)
        mirrorPrefixes.convention(emptyList())
    }

    @TaskAction
    fun resolve() {
        val sha = expectedSha256.get().trim().lowercase()
        val cacheFile = cachedArtifact.get().asFile
        val destFile = dest.orNull?.asFile
        val label = path

        // 1) 产物已在最终位置且内容正确：只补缓存，不联网（干净构建里这是最常见的路径）。
        if (destFile != null && Hashes.matches(destFile, sha)) {
            logger.lifecycle("$label: 复用已有产物 ${destFile.absolutePath}（sha256 校验通过，不联网）")
            publish(destFile, cacheFile)
            return
        }

        // 2) 预置来源（离线 / CI 手动指定的本地文件或目录）。
        for ((file, origin) in preloadCandidates(destFile)) {
            if (!file.isFile) continue
            if (!Hashes.matches(file, sha)) {
                logger.lifecycle("$label: 预置来源校验不通过，忽略 $origin")
                continue
            }
            logger.lifecycle("$label: 使用预置来源 $origin")
            publish(file, cacheFile)
            if (destFile != null) publish(cacheFile, destFile)
            return
        }

        // 3) 内容寻址缓存（`<sha256>`；同时兼容按产物名存放的旧缓存）。
        for (cached in cacheCandidates(cacheFile, destFile)) {
            if (!Hashes.matches(cached, sha)) continue
            logger.lifecycle("$label: 命中本地缓存 ${cached.absolutePath}（不联网）")
            publish(cached, cacheFile)
            if (destFile != null) publish(cacheFile, destFile)
            return
        }

        // 4) 网络：直连优先，镜像前缀依次兜底。
        if (offline) throw GradleException(offlineMessage(sha, cacheFile, destFile))

        val urls = LinkedHashSet<String>()
        sources.get().forEach { raw ->
            urls += raw
            mirrorPrefixes.get().forEach { prefix -> urls += prefix + raw }
        }
        val failures = mutableListOf<String>()
        for (url in urls) {
            var attempt = 0
            while (attempt < retries.get()) {
                attempt++
                try {
                    fetch(url, cacheFile, sha)
                    if (destFile != null) publish(cacheFile, destFile)
                    logger.lifecycle("$label: 已就绪 ${(destFile ?: cacheFile).absolutePath}（$sha）")
                    return
                } catch (e: Exception) {
                    val reason = e.message ?: e.toString()
                    failures += "$url#$attempt: $reason"
                    logger.lifecycle(
                        if (attempt < retries.get()) "$label: $url 失败（$reason），同源重试"
                        else "$label: $url 放弃（$reason）",
                    )
                }
            }
        }
        throw GradleException(
            "$label: 所有下载源均不可用（无网 / 未配代理 / 镜像不可达），期望 sha256=$sha。\n" +
                "试过的来源：\n" + failures.joinToString("\n") { "  - $it" } +
                "\n" + preloadHint(cacheFile, destFile),
        )
    }

    private fun preloadCandidates(destFile: File?): List<Pair<File, String>> {
        val out = mutableListOf<Pair<File, String>>()
        preloadFile.orNull?.asFile?.let { out += it to "预置文件 ${it.absolutePath}" }
        val dir = preloadDir.orNull?.asFile ?: return out
        val rel = preloadRelativePath.orNull ?: destFile?.name ?: return out
        out += File(dir, rel) to "预置目录 ${File(dir, rel).absolutePath}"
        destFile?.let {
            val flat = File(dir, it.name)
            if (flat.name != rel) out += flat to "预置目录 ${flat.absolutePath}"
        }
        return out
    }

    private fun cacheCandidates(cacheFile: File, destFile: File?): List<File> {
        val out = mutableListOf(cacheFile)
        destFile?.let { legacy -> out += File(cacheFile.parentFile, legacy.name) }
        return out
    }

    /** 复制到 [to]：先写同目录临时文件再原子改名，进程被杀不会留下半截文件冒充产物。 */
    private fun publish(from: File, to: File) {
        if (from.absoluteFile == to.absoluteFile) return
        if (to.isFile && to.length() == from.length() && Hashes.matches(to, expectedSha256.get())) return
        to.parentFile.mkdirs()
        val tmp = File.createTempFile(to.name + ".", ".part", to.parentFile)
        try {
            from.copyTo(tmp, overwrite = true)
            moveAtomically(tmp, to)
        } finally {
            tmp.delete()
        }
    }

    private fun fetch(url: String, target: File, sha: String) {
        target.parentFile.mkdirs()
        val tmp = File.createTempFile(target.name + ".", ".part", target.parentFile)
        var conn: HttpURLConnection? = null
        val started = System.currentTimeMillis()
        try {
            val u = URI(url).toURL()
            val p = proxy
            conn = (if (p != null) u.openConnection(p) else u.openConnection()) as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = connectTimeoutMs.get().toInt()
            conn.readTimeout = readTimeoutMs.get().toInt()
            conn.setRequestProperty("User-Agent", "dsh-mobile-gradle")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            conn?.disconnect()
        }
        val size = tmp.length()
        val wantBytes = expectedBytes.get()
        if (wantBytes > 0 && size != wantBytes) {
            tmp.delete()
            throw IOException("字节数不符：$size != $wantBytes")
        }
        val actual = Hashes.sha256(tmp)
        if (!actual.equals(sha, ignoreCase = true)) {
            tmp.delete()
            throw IOException("sha256 不符：$actual（$size B）")
        }
        moveAtomically(tmp, target)
        logger.lifecycle("$path: OK $url -> $size B / ${System.currentTimeMillis() - started} ms")
    }

    private fun moveAtomically(from: File, to: File) {
        to.parentFile.mkdirs()
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun preloadHint(cacheFile: File, destFile: File?): String = buildString {
        append("离线 / CI 预置方式：")
        append("把文件放到 ${cacheFile.absolutePath}（文件名必须是 sha256）")
        destFile?.let { append("，或直接放到 ${it.absolutePath}") }
        offlineHint.orNull?.let { append("，或 $it") }
        append("。")
    }

    private fun offlineMessage(sha: String, cacheFile: File, destFile: File?): String =
        "$path: --offline 且本地没有可用副本（期望 sha256=$sha）。\n" + preloadHint(cacheFile, destFile)

    private val proxy: Proxy? by lazy {
        // JVM 只认 -Dhttp.proxyHost 之类的系统属性，但 shell / CI 里配的是 HTTPS_PROXY；两种都认。
        val raw = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
            .firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
            ?: return@lazy null
        val hostPort = raw.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "8080").toIntOrNull() ?: return@lazy null
        if (host.isEmpty()) return@lazy null
        logger.lifecycle("$path: 走代理 $host:$port")
        Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }
}
