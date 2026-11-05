plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Android SDK / JVM 目标的唯一取值点。library 与 application 两类模块都从这里拿，
// 模块里不再出现第二份 compileSdk / minSdk / targetSdk / VERSION_17。
val dshCompileSdk = 35
val dshTargetSdk = 35
val dshMinSdk = 26

// 只放「不需要具体模块类型」的三项：defaultConfig 的 minSdk/targetSdk 由下面两个
// 具体类型分支各自写一行，避免在星投影的 CommonExtension<*, ...> 上直接取 defaultConfig。
fun com.android.build.api.dsl.CommonExtension<*, *, *, *, *, *>.applyDshAndroidSdk() {
    compileSdk = dshCompileSdk
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// 共享配置挂在两个插件 id 上：只挂 com.android.library 匹配不到 app 与 plugin-html
// 这两个 com.android.application 模块（它们曾经各自重写一份 compileSdk/minSdk/targetSdk）。
// 注意 plugin-html 本身是 library（插件实现），只有 plugin-html-apk 是 application。
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            applyDshAndroidSdk()
            defaultConfig {
                minSdk = dshMinSdk
                targetSdk = dshTargetSdk
            }
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            applyDshAndroidSdk()
            defaultConfig { minSdk = dshMinSdk }
        }
    }
    plugins.withId("org.jetbrains.kotlin.android") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }
}

// ── 发布隔离门禁（§17.7 R-5）：release 构建前先扫敏感关键词，命中即失败 ────────────
// 只接 assembleRelease：debug 是本机测试包，不设门禁。脚本零依赖、自己解析 git 路径；
// 找不到 node 时任务直接失败（exit 127），宁可构建不通过也不静默放行。
val dshLeakCheck = tasks.register<Exec>("dshLeakCheck") {
    group = "verification"
    description = "扫描仓库内跟踪文件里的敏感关键词，命中即让 release 构建失败。"
    // git 仓库根就是 app/；脚本放在仓库外的 scripts/ 下，所以走绝对路径。
    val repoRoot = rootProject.projectDir
    workingDir = repoRoot
    commandLine(
        providers.environmentVariable("NODE").getOrElse("node"),
        File(repoRoot.parentFile, "scripts/release/leak-check.mjs").absolutePath,
        "--root", repoRoot.absolutePath,
    )
    outputs.upToDateWhen { false }
}

subprojects {
    tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(dshLeakCheck) }
}
