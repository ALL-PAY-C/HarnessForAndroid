plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dsh.mobile.runtimehost"
}

dependencies {
    // 公共签名里没有 core:model / core:protocol 的类型（runtime-host/src 全模块 grep 0 命中）：
    // 两条 api 已删。需要 RuntimeState / LineChannel 的模块（app、feature:*）各自直接声明。
    // core:platform 仅内部 AppLog，core:download 仅内部 ZipExtract → implementation。
    implementation(project(":core:platform"))
    implementation(project(":core:download"))
    implementation(project(":node-runtime"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证 JSON 往返。
    testImplementation(libs.org.json)
}
