plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dsh.mobile.runtimehost"
}

dependencies {
    api(project(":core:protocol"))
    // 公共签名只暴露 core:model 的 RuntimeState 与 core:protocol 的 LineChannel；
    // core:common 仅内部 AppLog → implementation。
    implementation(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":node-runtime"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证 JSON 往返。
    testImplementation(libs.org.json)
}
