import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import java.io.File
import java.util.Properties
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// engine/ 是运行期 Node 引擎的源码树（含 node_modules）。APK 里要的是打包后的
// assets/runtime/engine.zip（aapt2 会跳过以 "." 开头的目录条目，所以必须打成 zip），
// 而这份 zip 过去只有手工跑 tools/pack-engine.ps1 才会产生 —— clone 后没人生成，
// 运行期 NodeEngineHost.unzipFromAssets 必失败。这里改成构建期产物：
// 输出落 build/generated/engine-assets/runtime/engine.zip，源码树里不再放二进制，
// inputs.dir(engine) 让 engine/ 一变就重打。
abstract class PackEngineAssetsZip : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val engineDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val engine = engineDir.get().asFile
        if (!engine.isDirectory) {
            throw GradleException(
                "engine/ 源码树不存在：" + engine.absolutePath + "，assets/runtime/engine.zip 无法生成",
            )
        }
        // 缺依赖时打出来的包装机后插件 import 必断，属于「构建成功但产物是坏的」。
        // 默认直接失败；确实要一个残缺包做别的验证时用 -PdshAllowEmptyEngine=true。
        if (!File(engine, "node_modules").isDirectory) {
            val allowEmpty = (project.findProperty("dshAllowEmptyEngine") as String?) == "true"
            val msg = "packEngineAssets: engine/node_modules 缺失，engine.zip 会缺运行期依赖" +
                "（设备端 ESM import 会断）。先 cd engine && npm ci；" +
                "确实要残缺包加 -PdshAllowEmptyEngine=true"
            if (allowEmpty) logger.warn(msg) else throw GradleException(msg)
        }
        // 与 tools/pack-engine.ps1 的 skipTop 对齐，另加 *.log。
        // node_modules 必须进包：现状 1833 条目里 1822 条来自它，排掉等于运行期无依赖。
        val skipTop = setOf("test", ".work", ".check.mjs", ".probe.mjs")
        // node_modules 里运行期用不到的：类型声明（.d.ts）、source map（.map）、
        // 上游自带的 test/tests 目录、README/CHANGELOG/LICENSE。合计约 7 MB 未压缩；
        // 运行期只 import .js/.json，这几类一个都不进 ESM 解析路径。
        fun deadWeightInNodeModules(rel: String): Boolean {
            if (!rel.startsWith("node_modules/")) return false
            val segments = rel.split('/')
            val name = segments.last().lowercase()
            if (name.endsWith(".d.ts") || name.endsWith(".map")) return true
            if (segments.subList(1, segments.size - 1).any { it == "test" || it == "tests" }) return true
            return name.startsWith("readme") || name.startsWith("changelog") ||
                name.startsWith("license") || name.startsWith("licence")
        }
        val relPaths = engine.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(engine).invariantSeparatorsPath }
            .filter { rel -> rel.substringBefore('/') !in skipTop && !rel.endsWith(".log") }
            .filter { rel -> !deadWeightInNodeModules(rel) }
            .sorted()
            .toList()

        val zipFile = outputDir.get().asFile.resolve("runtime/engine.zip")
        zipFile.parentFile.mkdirs()
        zipFile.delete()
        // 固定时间戳 + 排序：同样的 engine/ 打出同样的字节，构建缓存才认得出没变。
        val fixedTime = LocalDateTime.of(1981, 1, 1, 0, 0, 0)
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            relPaths.forEach { rel ->
                val entry = ZipEntry(rel)
                entry.setTimeLocal(fixedTime)
                zos.putNextEntry(entry)
                File(engine, rel).inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        logger.lifecycle(
            "packEngineAssets: " + zipFile.absolutePath + " entries=" + relPaths.size +
                " bytes=" + zipFile.length(),
        )
    }
}

// release 只发 arm64 设备包（lib/x86_64 在 androidComponents 里已排掉），x86_64 的两份内核
// 归档（node-v*-linux-x64-musl.tgz 与 dsh-kernel 的 x86_64 tgz，压缩后约 54 MB）进包就是死重。
// assets 没有 packaging.excludes 这种口子（AGP 8.7 只对 jniLibs/resources 开放），所以用
// Artifacts API 在 mergeReleaseAssets 之后、打包之前剔掉；debug 不走这个 transform，
// 四份归档照旧全在（模拟器要 x86_64）。
abstract class SlimReleaseKernelAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assetsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun slim() {
        val src = assetsDir.get().asFile
        val dst = outputDir.get().asFile
        dst.deleteRecursively()
        // 清单是 ABI 归属的唯一事实来源：abi != arm64-v8a 的归档一律不进 release。
        val manifestFile = File(src, "dsh-kernel/kernel-manifest.json")
        if (!manifestFile.isFile) {
            throw GradleException("slimReleaseKernelAssets: 缺 " + manifestFile.absolutePath + "，无法判断哪几份归档属于 arm64")
        }
        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        val rows = listOf("archives", "nodeArchives").flatMap { key ->
            (manifest[key] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        }
        val dead = rows.filter { (it["abi"] as? String) != "arm64-v8a" }
            .map { "dsh-kernel/" + (it["archive"] as String) }
            .toSet()
        val keep = rows.filter { (it["abi"] as? String) == "arm64-v8a" }
            .map { "dsh-kernel/" + (it["archive"] as String) }
            .toSet()

        var dropped = 0
        var droppedBytes = 0L
        var kept = 0
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src).invariantSeparatorsPath
            if (rel in dead) {
                dropped++
                droppedBytes += f.length()
            } else {
                val target = File(dst, rel)
                target.parentFile.mkdirs()
                f.copyTo(target, overwrite = true)
                kept++
            }
        }
        // 反向校验：清单里属于 arm64 的归档一个都不能少，否则是「排多了」的静默坏包。
        val missing = keep.filterNot { File(dst, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("slimReleaseKernelAssets: arm64 归档被误排：" + missing.joinToString(", "))
        }
        logger.lifecycle(
            "slimReleaseKernelAssets: 排除 " + dropped + " 个非 arm64 归档（" + droppedBytes +
                " B），保留 " + kept + " 个 assets 文件",
        )
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------- 签名身份（T1.7）----------
// 两套证书：
//   legacy  = app/keystore/app-release.jks（旧正式证书，入库）：已装旧包的设备只认这张证书。
//   release = 仓库外 %USERPROFILE%\.dsh\hfa-release\app-release.jks（新正式证书，口令只在仓库外）。
// 口令来源，按优先级：签名属性文件（默认仓库外 ~/.dsh/hfa-release/signing.properties，
// -PdshSigningProps=<file> 可改）→ 仓库内的本机遗留 app/keystore.properties（已不入库）→
// 环境变量。三处都没有对应口令时视为该证书不可用，逐级降级到 debug 证书，构建不中断。
val signingPropsFile: File = providers.gradleProperty("dshSigningProps").orNull
    ?.let { file(it) }
    ?: File(System.getProperty("user.home"), ".dsh/hfa-release/signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.isFile) signingPropsFile.inputStream().use { load(it) }
}
val localKeystoreProps = Properties().apply {
    val f = file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingProp(key: String, env: String): String? =
    signingProps.getProperty(key) ?: localKeystoreProps.getProperty(key) ?: System.getenv(env)

// .properties 里 \ 是转义符：D:\x\y.jks 会被读成 D:xy.jks，所以路径一律先换成 / 再解析。
fun keystoreFile(raw: String): File = file(raw.replace('\\', '/'))

// -PdshSigning=legacy|release，默认 legacy：不带参数构建的包与已安装旧包同证书，能直接覆盖升级。
val signingMode: String = (providers.gradleProperty("dshSigning").orNull ?: "legacy").lowercase()
check(signingMode == "legacy" || signingMode == "release") {
    "-PdshSigning 只接受 legacy 或 release，收到：$signingMode"
}

// 旧证书：口令缺任一项即视为不可用（仓库里只有 .jks，没有口令）。
val repoLegacyKeystore: File = file("keystore/app-release.jks")
val legacyKeystore: File = signingProp("legacy.storeFile", "DSH_SIGNING_LEGACY_STOREFILE")
    ?.let { keystoreFile(it) } ?: repoLegacyKeystore
val legacyKeyAlias: String = signingProp("legacy.keyAlias", "DSH_SIGNING_LEGACY_KEYALIAS") ?: "dsh-release"
val legacyStorePass: String? = signingProp("legacy.storePassword", "DSH_SIGNING_LEGACY_STOREPASS")
val legacyKeyPass: String? = signingProp("legacy.keyPassword", "DSH_SIGNING_LEGACY_KEYPASS")
val hasLegacySigning = legacyKeystore.isFile && legacyStorePass != null && legacyKeyPass != null

// 新证书：默认仓库外路径，-PdshReleaseStoreFile / 签名属性文件可覆盖。
val releaseKeystore: File = providers.gradleProperty("dshReleaseStoreFile").orNull
    ?.let { keystoreFile(it) }
    ?: signingProp("release.storeFile", "DSH_SIGNING_RELEASE_STOREFILE")?.let { keystoreFile(it) }
    ?: File(System.getProperty("user.home"), ".dsh/hfa-release/app-release.jks")
val releaseKeyAlias: String = signingProp("release.keyAlias", "DSH_SIGNING_RELEASE_KEYALIAS") ?: "dsh-release"
val releaseStorePass: String? = signingProp("release.storePassword", "DSH_SIGNING_RELEASE_STOREPASS")
val releaseKeyPass: String? = signingProp("release.keyPassword", "DSH_SIGNING_RELEASE_KEYPASS")
val hasReleaseSigning = releaseKeystore.isFile && releaseStorePass != null && releaseKeyPass != null

// 版本单一来源：gradle.properties 的 dshAppVersionName / dshAppVersionCode。
// defaultConfig、APK 文件名与 :plugin-html-apk（插件包）都读这一对值，仓库里只有一处可改。
val appVersionName: String = providers.gradleProperty("dshAppVersionName").get()
val appVersionCode: Int = providers.gradleProperty("dshAppVersionCode").get().toInt()
// 文件名只取版本号本身（"v0.01" 无空格，取到的就是 "v0.01"），中文与空格不进文件名。
val apkVersionTag = appVersionName.substringBefore(' ')

// 换证书的坑只在「同 applicationId 下已装旧包」时才咬人，构建期就把话说清楚，
// 别等到装机那一刻才看见 INSTALL_FAILED_UPDATE_INCOMPATIBLE（见 docs/signing.md）。
if (signingMode == "release" && repoLegacyKeystore.isFile) {
    logger.warn("WARNING: -PdshSigning=release 换用了新正式证书，而仓库里仍有旧证书 $repoLegacyKeystore。")
    logger.warn("WARNING: 已装旧包的设备（同 applicationId=dev.dsh.mobile，versionCode=$appVersionCode）")
    logger.warn("WARNING: 升级时会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE —— 必须卸载旧包或改用新的 applicationId。")
}

android {
    namespace = "dev.dsh.mobile.app"

    // compileSdk / minSdk / targetSdk 由根 build.gradle.kts 的 subprojects 块统一给（含本模块）。
    defaultConfig {
        applicationId = "dev.dsh.mobile"
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Committed debug key: keeps the certificate stable across machines and
        // build homes, so a new APK upgrades the previous install instead of
        // failing with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        create("local") {
            storeFile = file("keystore/dsh-debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // 三套签名方案全开：v1 给老系统与部分安装器（只带 v2 的包在它们眼里就是「无效」），
            // v2 是 Android 7+ 的主路径，v3 带密钥轮换信息。
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        // 旧正式证书：只为了让已安装旧包的设备能覆盖升级，口令来自仓库外（或环境变量）。
        if (hasLegacySigning) {
            create("legacy") {
                storeFile = legacyKeystore
                storePassword = legacyStorePass
                keyAlias = legacyKeyAlias
                keyPassword = legacyKeyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
        // 新正式证书：keystore 与口令都在仓库外，任何被跟踪文件里都没有口令。
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // 缺证书时逐级降级：release（新）→ legacy（旧）→ local（debug），干净 CI / 别人的机器也能出包。
    val debugSigning = signingConfigs.getByName("local")
    val legacySigning = signingConfigs.findByName("legacy") ?: debugSigning
    val releaseSigning = signingConfigs.findByName("release") ?: legacySigning
    // 一个开关决定本模块所有 buildType 的签名身份：debug 与 release 必须同证书，
    // 才能相互覆盖升级（两者 applicationId 不同，是各自独立的两个包）。
    val activeSigning = if (signingMode == "release") releaseSigning else legacySigning

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = activeSigning
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = activeSigning
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // 引擎跑在 :node 独立进程里，跨进程接口走 AIDL。
        aidl = true
    }

    // T1.2：不再用 sourceSets 跨模块挂 plugin-html 的源码（同一份 Kotlin 编两次、
    // 版本双源）。plugin-html 现在是普通 com.android.library，走 project 依赖进本模块
    // （下面的 implementation(project(":plugin-html"))）；它的 src/main/resources
    // （render.js / diagram/*.js）随 aar 进 APK 的 Java 资源，RenderScript 的
    // Class.getResourceAsStream("/render.js") 路径不变。要独立发布的插件 APK 由
    // :plugin-html-apk 这个外壳模块产出。
    // 本文件唯一的 packaging 块（原本两个：一个只写 jniLibs、一个只写 resources，现合为一处）。
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        // 部分原生件是可执行文件而不是共享库：必须解压到 nativeLibraryDir（files/ 是 noexec）。
        // 内置网关的 libgateway.so 靠这个开关随安装解压出来（Android 10+ 禁止 exec filesDir）。
        jniLibs.useLegacyPackaging = true
    }
}

// release 产物固定命名 HarnessForAndroid-release-v<版本号>.apk（AGP 8.x Variant API）。
// outputFileName 目前只在 VariantOutputImpl 上，公开接口里没有，故按实现类设置。
val packEngineAssets = tasks.register<PackEngineAssetsZip>("packEngineAssets") {
    group = "build"
    description = "把 engine/ 打包成 assets/runtime/engine.zip（构建期生成，不进源码树）"
    engineDir.set(layout.projectDirectory.dir("../engine"))
    outputDir.set(layout.buildDirectory.dir("generated/engine-assets"))
}

val slimReleaseKernelAssets = tasks.register<SlimReleaseKernelAssets>("slimReleaseKernelAssets") {
    group = "build"
    description = "release 打包前剔掉 kernel-manifest.json 里非 arm64-v8a 的内核归档"
}

androidComponents {
    // 生成的 engine-assets 目录挂到每个 variant 的 assets：AGP 自己补任务依赖，
    // 不必手写 mergeAssets.dependsOn（接线错一次就没有 engine.zip 的静默坏包）。
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            packEngineAssets,
            PackEngineAssetsZip::outputDir,
        )
    }
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("HarnessForAndroid-release-$apkVersionTag.apk")
        }
        // release 只发 arm64 设备包：x86_64 的 libnode.so 有 117 MB，只服务模拟器调试。
        // 必须走 variant API：buildTypes 的 BuildType DSL 没有 packaging 块（AGP 8.7），
        // 写在里面会落到 android.packaging 上，连 debug 一起排掉。
        variant.packaging.jniLibs.excludes.add("lib/x86_64/**")
        // 依赖附带（androidx/datastore）的 32 位/模拟器小 so 也一并排掉：release 只发 arm64。
        variant.packaging.jniLibs.excludes.add("lib/armeabi-v7a/**")
        variant.packaging.jniLibs.excludes.add("lib/x86/**")
        // 同一套理由往上一步：只含 arm64-v8a 的包里，x86_64 的两份内核归档是死重。
        // transform 只挂在 release 上，debug 的 mergeDebugAssets 不受影响。
        variant.artifacts.use(slimReleaseKernelAssets)
            .wiredWithDirectories(
                SlimReleaseKernelAssets::assetsDir,
                SlimReleaseKernelAssets::outputDir,
            )
            .toTransform(SingleArtifact.ASSETS)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:protocol"))
    implementation(project(":core:data"))
    implementation(project(":design"))
    implementation(project(":render"))
    implementation(project(":runtime-host"))
    implementation(project(":node-runtime"))
    implementation(project(":toolbridge"))
    implementation(project(":linux-runtime"))
    implementation(project(":plugin-api"))
    implementation(project(":plugin-host"))
    // 内置 HTML 渲染插件：HtmlPlugin 类来自这个 library，运行时由 AppContainer 当内置插件注册。
    implementation(project(":plugin-html"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:agents"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:plugins"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    debugImplementation(libs.compose.tooling)
}
