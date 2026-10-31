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

// 正式签名参数来自 app/keystore.properties（不进版本库）。文件缺失时（干净的 CI、
// 别人的机器）退回下面仓库内的 debug keystore，release 仍能构建；口令绝不硬编码。
val releaseKeystoreProps = Properties().apply {
    val f: File = file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = releaseKeystoreProps.getProperty("storeFile")
    ?.let { file(it).isFile } == true

// 版本单一来源：defaultConfig 与 APK 文件名都读这里，改一处即可。
val appVersionName = "v0.08"
val appVersionCode = 95
// 文件名只取版本号本身（"v0.01" 无空格，取到的就是 "v0.01"），中文与空格不进文件名。
val apkVersionTag = appVersionName.substringBefore(' ')

android {
    namespace = "dev.dsh.mobile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.dsh.mobile"
        minSdk = 26
        targetSdk = 35
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
        if (hasReleaseKeystore) {
            create("releaseSelf") {
                storeFile = file(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // debug 与 release 走同一张证书：证书一致才能相互覆盖升级。两者 applicationId
    // 不同（.debug 后缀），所以是两个独立包，各自升级、互不覆盖，这也是 Android 常规做法。
    val sharedSigning = if (hasReleaseKeystore) {
        signingConfigs.getByName("releaseSelf")
    } else {
        signingConfigs.getByName("local")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = sharedSigning
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = sharedSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // 引擎跑在 :node 独立进程里，跨进程接口走 AIDL。
        aidl = true
    }

    sourceSets {
        // 内置 HTML 渲染插件：plugin-html 仍是可独立发布的 APK 模块（com.android.application，
        // 改成 library 会毁掉它的发布产物），所以这里把它的 Kotlin 源码与 Java 资源
        // （render.js，RenderScript 按 classpath "/render.js" 读取）直接纳进 app 的编译单元。
        // 运行时由 AppContainer 当内置插件注册，见 pluginHost。
        getByName("main") {
            kotlin.srcDir("../plugin-html/src/main/kotlin")
            // 资源必须进 resources 而不是 java：Java 源集只认 .kt/.java，把 resources
            // 目录挂到 java 上等于丢包（曾经的 render.js 与 diagram/*.js 就是这么丢的）。
            resources.srcDir("../plugin-html/src/main/resources")
        }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        // 部分原生件是可执行文件而不是共享库：必须解压到 nativeLibraryDir（files/ 是 noexec）。
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
    testImplementation("junit:junit:4.13.2")
    implementation(project(":plugin-api"))
    implementation(project(":plugin-host"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:agents"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:plugins"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
