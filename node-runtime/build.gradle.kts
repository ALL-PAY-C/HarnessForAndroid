import dev.dsh.buildlogic.DownloadVerifiedArtifact
import dev.dsh.buildlogic.Hashes
import dev.dsh.buildlogic.Mirrors
import java.io.File
import java.util.zip.ZipFile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dsh-build-logic")
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

// 上游 zip 的内容指纹：缓存按它寻址，换源后也按它判定，校验不过就删掉换下一个源。
val libnodeZipUrl =
    "https://github.com/OpenFlun/nodejs-mobile/releases/download/v22.23.2/android-libnode.zip"
val libnodeZipSha256 = "76018df6050bba920ef0693badeef3b10a2021ea1bd21fded972865870e492d6"
val libnodeZipBytes = 83_255_613L

// 离线 / CI：指向本地已有的 android-libnode-*.zip，跳过一切网络访问。
val libnodeZipOverride =
    providers.environmentVariable("DSH_LIBNODE_ZIP")
        .orElse(providers.gradleProperty("dshLibnodeZip"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val libnodeCacheDir = gradle.gradleUserHomeDir.resolve("caches/dsh-nodejs-mobile")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
val libnodeSos: Map<String, File> = libnodeSha256.keys.sorted()
    .associateWith { File(jniLibsDir, "$it/libnode.so") }

// 下载 = 取到并校验 zip；解包 = 把 bin/<abi>/libnode.so 落进 src/main/jniLibs/<abi>/。
// 两者都有完整 inputs/outputs，缓存命中时不会联网也不会重复解包。
val downloadLibnode = tasks.register<DownloadVerifiedArtifact>("downloadLibnode") {
    group = "build"
    description = "取得 android-libnode.zip 并校验 sha256（缓存命中则不联网）"
    sources.set(listOf(libnodeZipUrl))
    mirrorPrefixes.set(Mirrors.GITHUB_RELEASE_PREFIXES)
    expectedSha256.set(libnodeZipSha256)
    expectedBytes.set(libnodeZipBytes)
    retries.set(2)
    readTimeoutMs.set(180_000)
    cacheDir.set(libnodeCacheDir)
    // 保留旧缓存路径：已下过 83 MB 的机器直接命中，不重下。
    dest.set(File(libnodeCacheDir, "android-libnode-v22.23.2.zip"))
    preloadFile.fileProvider(libnodeZipOverride.map { File(it) })
    offlineHint.set("-PdshLibnodeZip=<本地 android-libnode.zip> 或环境变量 DSH_LIBNODE_ZIP")
}

val unpackLibnode = tasks.register("unpackLibnode") {
    group = "build"
    description = "把 zip 里的 bin/<abi>/libnode.so 解到 src/main/jniLibs/<abi>/"
    dependsOn(downloadLibnode)
    inputs.file(downloadLibnode.flatMap { it.cachedArtifact })
    inputs.property("abis", libnodeSha256.keys.sorted())
    outputs.files(libnodeSos.values)
    doLast {
        val zipFile = downloadLibnode.get().cachedArtifact.get().asFile
        ZipFile(zipFile).use { zip ->
            libnodeSos.forEach { (abi, dest) ->
                // 上游 zip 里是 libnode/bin/<abi>/libnode.so（多一层 libnode/），
                // 兼容 "bin/<abi>/libnode.so" 的扁平变体，用后缀匹配两种都能命中。
                val suffix = "bin/$abi/libnode.so"
                val entry = zip.entries().asSequence()
                    .firstOrNull { it.name.replace('\\', '/').endsWith(suffix) }
                    ?: throw GradleException(
                        "libnode 压缩包内缺少 $suffix：${zipFile.absolutePath}" +
                            "（包内前几个条目：[" +
                            zip.entries().asSequence().take(5).joinToString(", ") { it.name } +
                            "]，zip 大小 ${zipFile.length()} B）",
                    )
                if (dest.isFile && dest.length() == entry.size) {
                    logger.lifecycle("unpackLibnode: 已存在 ${dest.absolutePath}")
                    return@forEach
                }
                dest.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                logger.lifecycle("unpackLibnode: 解出 ${entry.name} -> ${dest.absolutePath} (${dest.length()} B)")
            }
        }
    }
}

val verifyLibnodeSha256 = tasks.register("verifyLibnodeSha256") {
    group = "verification"
    description = "校验 src/main/jniLibs/<abi>/libnode.so 的 SHA-256 与预期一致"
    dependsOn(unpackLibnode)
    inputs.files(libnodeSos.values)
    inputs.property("expectedSha256", libnodeSha256)
    val stamp = layout.buildDirectory.file("verify/libnode-sha256.txt").get().asFile
    outputs.file(stamp)
    doLast {
        val failures = mutableListOf<String>()
        libnodeSha256.forEach { (abi, expected) ->
            val f = libnodeSos.getValue(abi)
            if (!f.isFile) {
                failures += "缺少 $abi/libnode.so：${f.absolutePath}"
                return@forEach
            }
            val actual = Hashes.sha256(f)
            logger.lifecycle("verifyLibnodeSha256: $abi/libnode.so -> $actual")
            if (!actual.equals(expected, ignoreCase = true)) {
                failures += "SHA-256 不匹配 $abi/libnode.so：实际 $actual，期望 $expected，文件 ${f.absolutePath}"
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException("libnode.so 校验失败：\n" + failures.joinToString("\n"))
        }
        stamp.parentFile.mkdirs()
        stamp.writeText(libnodeSha256.entries.joinToString("\n") { "${it.key} ${it.value}" })
        logger.lifecycle("verifyLibnodeSha256: OK - ${libnodeSha256.size} ABI")
    }
}

// 不再无条件挂 preBuild：只有真正消费 jniLibs 的任务（AGP 的原生库合并 / 打包链）才拉起
// 解包与校验。配置期、clean、:node-runtime:tasks 之类的调用不会触发任何下载。
tasks.configureEach {
    val consumesJniLibs = name.startsWith("merge") &&
        (name.endsWith("NativeLibs") || name.endsWith("JniLibFolders"))
    if (consumesJniLibs) dependsOn(verifyLibnodeSha256)
}
