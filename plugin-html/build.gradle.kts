plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 产物是一个「插件包」APK：没有 launcher activity，由宿主 dsh-mobile 用
// PathClassLoader 加载。因此 plugin-api 与 compose 全部 compileOnly ——
// 插件 APK 里不带这两份类，运行时一律解析到宿主那一份，类型同一性才成立。
android {
    namespace = "dev.dsh.mobile.plugin.html"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.dsh.mobile.plugin.html"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.0.0"
    }

    signingConfigs {
        // 复用宿主仓库里那把提交过的 debug key：插件包能直接 adb install，
        // 也能被宿主 PackageManager.getPackageArchiveInfo 正常解析。
        create("plugin") {
            storeFile = file("../app/keystore/dsh-debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("plugin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 插件包不是要发布的 app：compose/plugin-api 都是 compileOnly，
        // lint 会把它们当成缺失类，跑 release 时直接关掉。
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 双保险：即使哪天有人把 plugin-api 换成 implementation，也别把宿主那份打进插件。
        resources.excludes += "**/dev/dsh/mobile/pluginapi/**"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(project(":plugin-api"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    compileOnly(composeBom)
    compileOnly("androidx.compose.runtime:runtime")
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.foundation:foundation")
    compileOnly("androidx.compose.material3:material3")

    // 离线构建：compose 的传递依赖会把 annotation/collection 钉到 1.8.0 / 1.4.0，
    // 而这两个版本的 jar 不在本机 gradle 缓存里（宿主其它模块解析到的是 1.8.1 / 1.4.4）。
    // 显式抬到缓存里有的版本，插件包本来也不打包它们（compileOnly）。
    compileOnly("androidx.annotation:annotation:1.8.1")
    compileOnly("androidx.collection:collection:1.4.4")

    // 只为纯 JVM 的 HTML 生成器加：DiagramHtml/PlantUml 不碰 Android，能在 JVM 单测里直接断言。
    // 与仓库其它模块同一个版本；testImplementation 不参与插件 APK。
    testImplementation("junit:junit:4.13.2")

    // compose 编译器插件对 test 源集同样生效，它开编前要看到 compose runtime，
    // 而主源集用的是 compileOnly。照抄一份到 testCompileOnly，不引入任何新产物。
    testCompileOnly(composeBom)
    testCompileOnly("androidx.compose.runtime:runtime")
}
