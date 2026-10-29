import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dsh.mobile.noderuntime"

    // NDK 26.3 是本机唯一安装的版本；不写死的话 AGP 8.7 会去要 27.x。
    ndkVersion = "26.3.11579264"

    defaultConfig {
        // 只有这两个 ABI 有 libnode.so；不显式限制的话 AGP 会连 armeabi-v7a 一起编，
        // 然后因为缺 libnode.so 在 link 阶段失败。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        // libnode.so 是几十 MB 的 prebuilt，必须解压到 nativeLibraryDir 才能 dlopen。
        jniLibs.useLegacyPackaging = true
    }
}

// libnode.so 是第三方 fork 的 prebuilt（arm64 117,824,576 B / x86_64 122,936,592 B）。
// 换错文件或搬运被截断时只有设备端 dlopen 才会炸，所以在打包前用流式 SHA-256 拦一次。
val libnodeSha256 = mapOf(
    "arm64-v8a" to "5a8fdfa50fa8e638d9b5d1e61b77c6a76786821bc927a1f0a18e237f7728c892",
    "x86_64" to "9ec354b757403c6a3e7c8d6d5609f117a8f00d6ea84124d0f6181e56b873f1a5",
)

// 干净的 clone 里 jniLibs 下没有 libnode.so（117/122 MB 级 prebuilt 不进版本库）。
// 缺失时按优先级取一份 zip：外部指定（DSH_LIBNODE_ZIP / -PdshLibnodeZip）> Gradle user home
// 缓存（命中就完全不联网）> 网络（原始 release URL，直连不通再依次试国内镜像前缀）。
// 解出 bin/<abi>/libnode.so 放进 src/main/jniLibs/<abi>/，随后仍由下面既有的 sha256 校验拦一次。
// 两个 ABI 都在时本任务被 onlyIf 跳过，对既有构建零副作用。
val libnodeReleaseUrl =
    "https://github.com/OpenFlun/nodejs-mobile/releases/download/v22.23.2/android-libnode.zip"

// GitHub 直连在国内网络常超时；镜像只是把原始 URL 拼在前缀后面，任一个能下完就算成功。
val libnodeMirrorPrefixes = listOf(
    "https://gh-proxy.com/",
    "https://ghfast.top/",
    "https://hk.gh-proxy.com/",
)
val libnodeSources = listOf(libnodeReleaseUrl) + libnodeMirrorPrefixes.map { it + libnodeReleaseUrl }

// 离线 / CI：指向本地已有的 android-libnode-*.zip，跳过一切网络访问。
val libnodeZipOverride: String? =
    System.getenv("DSH_LIBNODE_ZIP")?.takeIf { it.isNotBlank() }
        ?: (findProperty("dshLibnodeZip") as String?)?.takeIf { it.isNotBlank() }

val downloadLibnode = tasks.register("downloadLibnode") {
    group = "build"
    description = "jniLibs/<abi>/libnode.so 缺失时取得上游 zip 并解出"
    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
    val cacheZip = gradle.gradleUserHomeDir
        .resolve("caches/dsh-nodejs-mobile/android-libnode-v22.23.2.zip")
    onlyIf {
        libnodeSha256.keys.any { abi -> !File(jniLibsDir, abi + "/libnode.so").isFile }
    }
    doLast {
        // JVM 只认 -Dhttp.proxyHost 之类的系统属性，不认环境变量；但 shell / CI 里配的是
        // HTTPS_PROXY。两种都认，直连不通时不用为构建脚本改代码。
        val proxy: Proxy? = run {
            val raw = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
                .firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
                ?: return@run null
            val hostPort = raw.trim().removePrefix("http://").removePrefix("https://")
                .removeSuffix("/")
            val host = hostPort.substringBefore(':')
            val port = hostPort.substringAfter(':', "8080").toIntOrNull() ?: return@run null
            if (host.isEmpty()) return@run null
            logger.lifecycle("downloadLibnode: 走代理 " + host + ":" + port)
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }

        // 单个 URL：GET 一次，失败同源重试一次；HTTPS 重定向（release 资源会 302 到
        // release-assets.githubusercontent.com）由 HttpURLConnection 自己跟。
        // 先下到同目录临时文件，成功后才落成缓存 zip：进程被强杀（daemon stop / Ctrl-C）
        // 不会留下半截文件冒充缓存——下次构建会拿一个坏 zip 去解包。
        fun fetchOne(url: String, finalDest: File) {
            // 干净的 CI runner 上缓存目录还不存在：createTempFile 会直接抛
            // "No such file or directory"，看起来像所有下载源都挂了。
            finalDest.parentFile.mkdirs()
            // 临时文件名字带随机后缀：上一轮被强杀留下的半截文件可能仍有句柄，
            // 用固定名会被锁住（能写不能改名），换唯一名就绕开了。
            val dest = File.createTempFile(finalDest.name + ".", ".part", finalDest.parentFile)
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
                    conn.readTimeout = 180_000
                    conn.setRequestProperty("User-Agent", "dsh-mobile-gradle")
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP " + code)
                    dest.parentFile.mkdirs()
                    conn.inputStream.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    logger.lifecycle(
                        "downloadLibnode: OK " + url + " -> " + dest.length() + " B / " +
                            (System.currentTimeMillis() - started) + " ms",
                    )
                    dest.copyTo(finalDest, overwrite = true)
                    dest.delete()
                    return
                } catch (e: Exception) {
                    dest.delete()
                    if (attempt >= 2) throw IOException(e.message ?: e.toString(), e)
                    logger.lifecycle(
                        "downloadLibnode: 失败（" + (e.message ?: e.toString()) + "），同源重试一次",
                    )
                } finally {
                    conn?.disconnect()
                }
            }
        }

        // 缓存 zip 是不是真的能用（半截文件 / 空壳都判 false，触发重新下载）。
        fun zipOk(f: File): Boolean = try {
            if (!f.isFile || f.length() == 0L) false
            else ZipFile(f).use { z ->
                val names = z.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
                libnodeSha256.keys.all { abi -> names.any { it.endsWith("bin/" + abi + "/libnode.so") } }
            }
        } catch (e: Exception) {
            logger.lifecycle("downloadLibnode: 缓存 zip 不可用（" + (e.message ?: "") + "），重新下载")
            false
        }

        val overrideFile = libnodeZipOverride?.let { File(it) }
        val zipInUse: File = when {
            overrideFile != null -> {
                if (!overrideFile.isFile) {
                    throw GradleException(
                        "libnode zip 覆盖路径不存在：" + overrideFile.absolutePath +
                            "（来自 DSH_LIBNODE_ZIP / -PdshLibnodeZip）",
                    )
                }
                logger.lifecycle("downloadLibnode: 用外部指定的 zip " + overrideFile.absolutePath)
                if (!cacheZip.isFile || cacheZip.length() == 0L) {
                    // 存进缓存，后续离线构建不用再指路径。
                    cacheZip.parentFile.mkdirs()
                    overrideFile.copyTo(cacheZip, overwrite = true)
                }
                overrideFile
            }
            zipOk(cacheZip) -> {
                logger.lifecycle("downloadLibnode: 复用缓存 " + cacheZip.absolutePath + "（不联网）")
                cacheZip
            }
            else -> {
                val tried = mutableListOf<String>()
                var ok = false
                for (pass in 1..2) {
                    for (url in libnodeSources) {
                        tried += url
                        try {
                            fetchOne(url, cacheZip)
                            ok = true
                            break
                        } catch (e: Exception) {
                            logger.lifecycle("downloadLibnode: " + url + " 失败：" + (e.message ?: ""))
                        }
                    }
                    if (ok) break
                    if (pass == 1) logger.lifecycle("downloadLibnode: 全部源失败，整轮再试一次")
                }
                if (!ok || !cacheZip.isFile) {
                    throw GradleException(
                        "libnode.so 下载失败：所有下载源均不可用（无网 / 未配代理 / 镜像不可达）。\n" +
                            "试过的 URL：\n" +
                            tried.distinct().joinToString("\n") { "  - " + it } +
                            "\n离线环境请用 -PdshLibnodeZip=<本地 android-libnode.zip> 或环境变量 DSH_LIBNODE_ZIP。",
                    )
                }
                logger.lifecycle(
                    "downloadLibnode: 已缓存 " + cacheZip.absolutePath + " (" + cacheZip.length() + " B)",
                )
                cacheZip
            }
        }

        ZipFile(zipInUse).use { zip ->
            libnodeSha256.keys.sorted().forEach { abi ->
                val dest = File(jniLibsDir, abi + "/libnode.so")
                if (dest.isFile) {
                    logger.lifecycle("downloadLibnode: 已存在 " + dest.absolutePath)
                    return@forEach
                }
                // 上游 zip 里的布局是 libnode/bin/<abi>/libnode.so（多一层 libnode/）；
                // 兼容 "bin/<abi>/libnode.so" 的扁平变体，用后缀匹配两种都能命中。
                val suffix = "bin/" + abi + "/libnode.so"
                val entry = zip.entries().asSequence()
                    .firstOrNull { it.name.replace('\\', '/').endsWith(suffix) }
                    ?: throw GradleException(
                        "libnode 压缩包内缺少 " + suffix + "：" + zipInUse.absolutePath +
                            "（包内前几个条目：[" +
                            zip.entries().asSequence().take(5).joinToString(", ") { it.name } + "]" +
                            "），zip 大小 " + zipInUse.length() + " B",
                    )
                dest.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                logger.lifecycle(
                    "downloadLibnode: 解出 " + entry.name + " -> " + dest.absolutePath +
                        " (" + dest.length() + " B)",
                )
            }
        }
    }
}

val verifyLibnodeSha256 = tasks.register("verifyLibnodeSha256") {
    group = "verification"
    description = "校验 src/main/jniLibs/<abi>/libnode.so 的 SHA-256 与预期一致"
    // 文件缺失时先下载解包，再走下面的比对（下载后校验不过同样报错）。
    dependsOn(downloadLibnode)
    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
    inputs.property("expectedSha256", libnodeSha256)
    inputs.files(libnodeSha256.keys.map { File(jniLibsDir, it + "/libnode.so") })
    doLast {
        val failures = mutableListOf<String>()
        libnodeSha256.forEach { (abi, expected) ->
            val f = File(jniLibsDir, abi + "/libnode.so")
            if (!f.isFile) {
                failures += "缺少 " + abi + "/libnode.so：" + f.absolutePath
                return@forEach
            }
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            logger.lifecycle("verifyLibnodeSha256: " + abi + "/libnode.so -> " + actual)
            if (!actual.equals(expected, ignoreCase = true)) {
                failures += "SHA-256 不匹配 " + abi + "/libnode.so：实际 " + actual +
                    "，期望 " + expected + "，文件 " + f.absolutePath
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException("libnode.so 校验失败：\n" + failures.joinToString("\n"))
        }
        logger.lifecycle("verifyLibnodeSha256: OK - " + libnodeSha256.size + " ABI")
    }
}

// 本模块任何构建（含 :app:assembleDebug / assembleRelease 带起来的任务链）都先过校验。
afterEvaluate {
    tasks.named("preBuild") { dependsOn(verifyLibnodeSha256) }
}
