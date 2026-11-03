plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 插件实现本体，普通 library，两个消费方：
//   1) :app 直接 implementation(project(":plugin-html"))，当内置插件注册（HtmlPlugin）；
//   2) :plugin-html-apk 把它打成可独立安装的插件包 APK。
// 以前它是 com.android.application，app 用 sourceSets 把这里的源码挂进自己的编译单元，
// 同一份 Kotlin 编两次、插件版本与宿主版本各写一份（T1.2 去掉这两条）。
// plugin-api 与 compose 全部 compileOnly：运行时一律解析到宿主那一份，类型同一性才成立。
android {
    namespace = "dev.dsh.mobile.plugin.html"

    // compileSdk / minSdk / targetSdk 由根 build.gradle.kts 的 subprojects 块统一给。
    // 本模块没有任何代码读 BuildConfig / R，关掉能少一个产物。
    buildFeatures { buildConfig = false }

    lint {
        // 插件实现里 compose/plugin-api 都是 compileOnly，lint 会把它们当缺失类。
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        // 双保险：即使哪天有人把 plugin-api 换成 implementation，也别把宿主那份打进包里。
        resources.excludes += "**/dev/dsh/mobile/pluginapi/**"
    }
}

dependencies {
    compileOnly(project(":plugin-api"))
    // AppLog 直接取自 core:common，必须显式声明：以前靠 plugin-api 的 api(core:common)
    // 泄漏拿到，T1.2 把那条例行删了，插件这里就得自己声明（compileOnly，宿主提供实现）。
    compileOnly(project(":core:common"))

    val composeBom = platform(libs.compose.bom)
    compileOnly(composeBom)
    compileOnly(libs.compose.runtime)
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.material3)

    // 离线构建：compose 的传递依赖会把 annotation/collection 钉到 1.8.0 / 1.4.0，
    // 而这两个版本的 jar 不在本机 gradle 缓存里（宿主其它模块解析到的是 1.8.1 / 1.4.4）。
    // 显式抬到缓存里有的版本，插件包本来也不打包它们（compileOnly）。
    compileOnly(libs.androidx.annotation)
    compileOnly(libs.androidx.collection)

    // 纯 JVM 的 HTML 生成器（DiagramHtml/PlantUml）不碰 Android，能在 JVM 单测里直接断言。
    testImplementation(libs.junit)

    // compose 编译器插件对 test 源集同样生效，它开编前要看到 compose runtime，
    // 而主源集用的是 compileOnly。照抄一份到 testCompileOnly，不引入任何新产物。
    testCompileOnly(composeBom)
    testCompileOnly(libs.compose.runtime)
}
