import dev.dsh.buildlogic.DownloadVerifiedArtifact
import dev.dsh.buildlogic.Mirrors
import groovy.json.JsonSlurper
import java.io.File

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dsh-build-logic")
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
    // core:common 只有 ProxyEnv（LinuxNativeProbe / ProotChannel 内部用）→ implementation。
    implementation(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ── 内核归档补齐：clean clone 的最后一个断点 ──────────────────────────────────
// kernel-manifest.json 是唯一事实来源。dsh-kernel 的两个 tgz 与 node 的两个 musl tgz
// 都被 .gitignore 排除（合计约 105 MB），clone 出来只剩清单，APK 里就会缺内核。
// 每个归档一个 DownloadVerifiedArtifact：来源顺序 = 已存在且校验通过 > 预置目录（离线 / CI）
// > Gradle user home 缓存 > release 资产 + 镜像前缀，全部按清单的 bytes + sha256 判定。
// node 归档的 nodeSha256 描述的是「剥掉 include/ 与 CHANGELOG 后重打包」的 tgz，
// 与 nodejs.org 上的原始 .tar.xz 不是同一份字节，所以它也从本项目的 release 取；
// 清单里的 nodeSource 只作为来源记录。
data class KernelArchive(
    val abi: String,
    val relativePath: String,
    val sha256: String,
    val bytes: Long,
    val url: String,
)

val kernelAssetsRoot: File = layout.projectDirectory.dir("src/main/assets/dsh-kernel").asFile
val kernelManifestFile = File(kernelAssetsRoot, "kernel-manifest.json")
val kernelReleaseBase =
    "https://github.com/ALL-PAY-C/HarnessForAndroid/releases/download/assets-kernel-v0.2.1-rc.2/"
val kernelCacheDir = gradle.gradleUserHomeDir.resolve("caches/dsh-kernel")

// 离线 / CI：指向一个已经放了四个归档的目录，完全跳过网络。
val kernelAssetsOverrideDir =
    providers.environmentVariable("DSH_KERNEL_ASSETS_DIR")
        .orElse(providers.gradleProperty("dshKernelAssetsDir"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { File(it) }

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
        return KernelArchive(
            abi = row["abi"] as String,
            relativePath = rel,
            sha256 = (row["sha256"] as String).lowercase(),
            bytes = (row["bytes"] as Number).toLong(),
            url = kernelReleaseBase + File(rel).name,
        )
    }
    rows("archives").map(::toArchive) + rows("nodeArchives").map(::toArchive)
}

// 每个归档一个独立任务（各自 inputs/outputs），聚合任务保留原名字给脚本 / 文档用。
val downloadKernelArchives = tasks.register("downloadKernelArchives") {
    group = "build"
    description = "按 kernel-manifest.json 补齐 dsh-kernel / node 的四个归档（缺失时才联网）"
}

kernelArchives.forEach { archive ->
    val taskName = "downloadKernel_" + File(archive.relativePath).name
        .removeSuffix(".tgz")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
    val dest = File(kernelAssetsRoot, archive.relativePath)
    val task = tasks.register<DownloadVerifiedArtifact>(taskName) {
        group = "build"
        description = "取得 ${archive.relativePath} 并校验 sha256（缓存命中则不联网）"
        sources.set(listOf(archive.url))
        mirrorPrefixes.set(Mirrors.GITHUB_RELEASE_PREFIXES)
        expectedSha256.set(archive.sha256)
        expectedBytes.set(archive.bytes)
        retries.set(2)
        readTimeoutMs.set(300_000)
        cacheDir.set(kernelCacheDir)
        this.dest.set(dest)
        preloadRelativePath.set(archive.relativePath)
        preloadDir.fileProvider(kernelAssetsOverrideDir)
        offlineHint.set(
            "-PdshKernelAssetsDir=<目录> 或环境变量 DSH_KERNEL_ASSETS_DIR" +
                "（目录里放 ${archive.relativePath} 或它的文件名）",
        )
    }
    downloadKernelArchives.configure { dependsOn(task) }
}

// 不再无条件挂 preBuild：只有真正消费 assets 的任务（AGP 的资源合并 / 打包链）才拉起补齐。
// 配置期、clean、:linux-runtime:tasks 之类的调用不触发任何下载。
tasks.configureEach {
    // 消费 kernel assets 的除了 AGP 的资源合并 / 打包链，还有 lint 的模型构建：
    // lint 会读 assets 目录生成模型，但它的任务名不以 Assets 结尾，早先没被挂上依赖，
    // 于是 :app:assembleRelease 在全量图里报
    // "uses this output of task :linux-runtime:downloadKernel_* without declaring an explicit
    // or implicit dependency"（单独跑 lint 任务反而通过，因为那时资产已在盘上）。
    // lint 一族的任务名大小写不统一（generateReleaseLintModel / lintVitalAnalyzeRelease），
    // 逐个枚举前缀会一直漏；统一按小写包含 "lint" 判定。
    val consumesAssets = (name.endsWith("Assets") &&
        (name.startsWith("merge") || name.startsWith("package"))) ||
        name.lowercase().contains("lint")
    if (consumesAssets) dependsOn(downloadKernelArchives)
}
