import groovy.json.JsonSlurper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dsh.mobile.linux"
    defaultConfig {
        // proot / rootfs 只提供 64 位 ABI
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }
}

dependencies {
    // 单向依赖：linux-runtime -> toolbridge（toolbridge 不得反向依赖本模块）
    api(project(":toolbridge"))
    api(project(":core:common"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// ── 内核归档补齐：clean clone 的最后一个断点 ──────────────────────────────────
// kernel-manifest.json 是唯一事实来源。dsh-kernel 的两个 tgz 与 node 的两个 musl tgz
// 都被 .gitignore 排除（合计约 105 MB），clone 出来只剩清单，APK 里就会缺内核。
// 本任务按清单补四个归档，来源顺序：
//   1) DSH_KERNEL_ASSETS_DIR / -PdshKernelAssetsDir 指定的本地目录（离线 / CI）
//   2) Gradle user home 缓存 caches/dsh-kernel/
//   3) 本项目 release 资产 assets-kernel-v0.2.1-rc.2（scripts/fetch-dsh-kernel.sh 的产物）
//   4) 国内镜像前缀包一层上面的 release URL
// 取到后一律按清单的 bytes + sha256 校验，不符即删除半成品并换下一个源。
// node 归档的 nodeSha256 描述的是「剥掉 include/ 与 CHANGELOG 后重打包」的 tgz，
// 与 nodejs.org 上的原始 .tar.xz 不是同一份字节，所以它也从本项目的 release 取；
// 清单里的 nodeSource 只作为来源记录。
// 四个都在且校验通过时本任务 SKIPPED，对既有构建零副作用。
data class KernelArchive(
    val abi: String,
    val relativePath: String,
    val sha256: String,
    val bytes: Long,
    val remoteSources: List<String>,
)

val kernelAssetsRoot: File = layout.projectDirectory.dir("src/main/assets/dsh-kernel").asFile
val kernelManifestFile = File(kernelAssetsRoot, "kernel-manifest.json")
val kernelReleaseBase =
    "https://github.com/ALL-PAY-C/HarnessForAndroid/releases/download/assets-kernel-v0.2.1-rc.2/"
val kernelMirrorPrefixes = listOf(
    "https://gh-proxy.com/",
    "https://ghfast.top/",
    "https://hk.gh-proxy.com/",
)

// 离线 / CI：指向一个已经放了四个归档的目录，完全跳过网络。
val kernelAssetsOverrideDir: String? =
    System.getenv("DSH_KERNEL_ASSETS_DIR")?.takeIf { it.isNotBlank() }
        ?: (findProperty("dshKernelAssetsDir") as String?)?.takeIf { it.isNotBlank() }

fun kernelFileSha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun kernelArchiveOk(archive: KernelArchive): Boolean {
    val f = File(kernelAssetsRoot, archive.relativePath)
    if (!f.isFile || f.length() != archive.bytes) return false
    return kernelFileSha256(f).equals(archive.sha256, ignoreCase = true)
}

val kernelArchives: List<KernelArchive> = run {
    if (!kernelManifestFile.isFile) {
        throw GradleException("缺少内核清单：" + kernelManifestFile.absolutePath)
    }
    val manifest = JsonSlurper().parse(kernelManifestFile) as Map<*, *>
    fun rows(key: String): List<Map<*, *>> =
        (manifest[key] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

    fun toArchive(row: Map<*, *>): KernelArchive {
        val rel = row["archive"] as String
        // 本地按清单里的相对路径摆放（nodeArchives 带 node/ 前缀），但 release 资产是扁平名，
        // 直接用 rel 拼会 404；URL 只用文件名。
        val url = kernelReleaseBase + File(rel).name
        return KernelArchive(
            abi = row["abi"] as String,
            relativePath = rel,
            sha256 = (row["sha256"] as String).lowercase(),
            bytes = (row["bytes"] as Number).toLong(),
            remoteSources = listOf(url) + kernelMirrorPrefixes.map { it + url },
        )
    }
    rows("archives").map(::toArchive) + rows("nodeArchives").map(::toArchive)
}

val downloadKernelArchives = tasks.register("downloadKernelArchives") {
    group = "build"
    description = "按 kernel-manifest.json 补齐 dsh-kernel / node 的四个归档（缺失时才联网）"
    onlyIf { kernelArchives.any { !kernelArchiveOk(it) } }
    doLast {
        // JVM 不认 HTTPS_PROXY 环境变量，但 shell / CI 里配的是它；两种都认。
        val proxy: Proxy? = run {
            val raw = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
                .firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
                ?: return@run null
            val hostPort = raw.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
            val host = hostPort.substringBefore(':')
            val port = hostPort.substringAfter(':', "8080").toIntOrNull() ?: return@run null
            if (host.isEmpty()) return@run null
            logger.lifecycle("downloadKernelArchives: 走代理 " + host + ":" + port)
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }
        val cacheDir = gradle.gradleUserHomeDir.resolve("caches/dsh-kernel")

        // 单个 URL：GET 一次，失败同源重试一次；release 资源的 302 由 HttpURLConnection 自己跟。
        fun download(url: String, dest: File) {
            var attempt = 0
            while (true) {
                attempt++
                var conn: HttpURLConnection? = null
                val started = System.currentTimeMillis()
                try {
                    val u = URI(url).toURL()
                    conn = (if (proxy != null) u.openConnection(proxy) else u.openConnection())
                        as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 300_000
                    conn.setRequestProperty("User-Agent", "dsh-mobile-gradle")
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP " + code)
                    dest.parentFile.mkdirs()
                    conn.inputStream.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    logger.lifecycle(
                        "downloadKernelArchives: OK " + url + " -> " + dest.length() + " B / " +
                            (System.currentTimeMillis() - started) + " ms",
                    )
                    return
                } catch (e: Exception) {
                    dest.delete()
                    if (attempt >= 2) throw IOException(e.message ?: e.toString(), e)
                } finally {
                    conn?.disconnect()
                }
            }
        }

        val failures = mutableListOf<String>()
        kernelArchives.forEach { archive ->
            val dest = File(kernelAssetsRoot, archive.relativePath)
            if (kernelArchiveOk(archive)) {
                logger.lifecycle("downloadKernelArchives: 已存在且校验通过 " + archive.relativePath)
                return@forEach
            }
            val cacheFile = File(cacheDir, File(archive.relativePath).name)
            val tried = mutableListOf<String>()

            // 候选文件通过校验才落到 assets 下，并顺带写入 Gradle 缓存。
            fun accept(candidate: File, origin: String): Boolean {
                if (!candidate.isFile) return false
                val bytes = candidate.length()
                val hash = if (bytes == archive.bytes) kernelFileSha256(candidate) else null
                if (bytes != archive.bytes || hash == null || !hash.equals(archive.sha256, ignoreCase = true)) {
                    failures += archive.relativePath + " 从 " + origin + " 取到的文件校验不通过（" +
                        bytes + " B, sha256=" + (hash ?: "-") + "）"
                    return false
                }
                dest.parentFile.mkdirs()
                candidate.copyTo(dest, overwrite = true)
                if (candidate.canonicalPath != cacheFile.canonicalPath) {
                    cacheFile.parentFile.mkdirs()
                    dest.copyTo(cacheFile, overwrite = true)
                }
                logger.lifecycle(
                    "downloadKernelArchives: " + archive.relativePath + " <- " + origin +
                        " (" + dest.length() + " B, sha256 校验通过)",
                )
                return true
            }

            var done = false
            // 1) 外部目录（离线 / CI 预置）
            val overrideDir = kernelAssetsOverrideDir?.let { File(it) }
            if (overrideDir != null && !done) {
                tried += overrideDir.absolutePath + "（DSH_KERNEL_ASSETS_DIR / -PdshKernelAssetsDir）"
                done = listOf(
                    File(overrideDir, archive.relativePath),
                    File(overrideDir, File(archive.relativePath).name),
                ).any { accept(it, "本地目录 " + it.absolutePath) }
            }
            // 2) Gradle user home 缓存
            if (!done) {
                tried += cacheFile.absolutePath
                done = accept(cacheFile, "缓存 " + cacheFile.absolutePath)
            }
            // 3) release 原始 URL，4) 镜像前缀
            for (url in archive.remoteSources) {
                if (done) break
                tried += url
                val part = File(dest.parentFile, dest.name + ".part")
                try {
                    download(url, part)
                } catch (e: Exception) {
                    logger.lifecycle("downloadKernelArchives: " + url + " 失败：" + (e.message ?: ""))
                    part.delete()
                    continue
                }
                done = accept(part, url)
                part.delete()
            }
            if (!done) {
                dest.delete()
                failures += archive.relativePath + " 无可用来源，试过：\n" +
                    tried.joinToString("\n") { "    - " + it }
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "内核归档补齐失败：\n" + failures.joinToString("\n") +
                    "\n离线环境请把四个归档放进一个目录并用 -PdshKernelAssetsDir=<dir> " +
                    "或环境变量 DSH_KERNEL_ASSETS_DIR 指定。",
            )
        }
        logger.lifecycle("downloadKernelArchives: 四个归档齐备（" + kernelArchives.size + " 个，均通过 sha256 校验）")
    }
}

// 与 node-runtime 的 verifyLibnodeSha256 同一做法：任何构建先走一遍补齐。
afterEvaluate {
    tasks.named("preBuild") { dependsOn(downloadKernelArchives) }
}
