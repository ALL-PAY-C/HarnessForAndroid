plugins {
    id("com.android.application")
}

// 插件包外壳：只承载 AndroidManifest + assets/plugin.json + 对 :plugin-html 的依赖。
// 目的：让「可独立安装的 dsh.html 插件 APK」继续存在，同时把插件实现拆成 app 也能
// 直接复用的 library（T1.2：不再用 sourceSets 把源码挂进 app 编第二遍）。
//
// 版本号跟随宿主：唯一来源是 gradle.properties 的 dshAppVersionName / dshAppVersionCode，
// 与 :app 读同一对值（原先 plugin-html 自己写 2.0.0/5，与 app 的 v0.08/102 双源）。
android {
    namespace = "dev.dsh.mobile.plugin.html.apk"

    // compileSdk / minSdk / targetSdk 由根 build.gradle.kts 统一给。
    defaultConfig {
        applicationId = "dev.dsh.mobile.plugin.html"
        versionCode = providers.gradleProperty("dshAppVersionCode").get().toInt()
        versionName = providers.gradleProperty("dshAppVersionName").get()
    }

    // 复用宿主仓库里那把提交过的 debug key：插件包能直接 adb install，
    // 也能被宿主的 PackageManager.getPackageArchiveInfo / PathClassLoader 正常解析。
    signingConfigs {
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

    lint {
        // 插件包不是要发布的 app：compose/plugin-api 都是 compileOnly，lint 会把它们
        // 当成缺失类，跑 release 时直接关掉。
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        // 宿主那份 plugin-api 绝不进插件包（类型同一性靠它）。
        resources.excludes += "**/dev/dsh/mobile/pluginapi/**"
    }
}

dependencies {
    // 插件实现全部来自这里；它的 compileOnly 依赖（plugin-api / compose）不随本模块打包，
    // 运行时由宿主提供。
    implementation(project(":plugin-html"))
}
