plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "dev.dsh.mobile.core.protocol"
}
dependencies {
    api(project(":core:model"))
    // core:common 只在实现里用（AppLog 等），公共签名（LineChannel / 协议模型）不出现
    // core.common 类型 → implementation。
    implementation(project(":core:common"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证引擎 RPC 的 JSON 往返
    // （与 core:data / plugin-host / runtime-host 同做法）。
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
