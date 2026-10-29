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
    api(project(":core:common"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证引擎 RPC 的 JSON 往返
    // （与 core:data / plugin-host / runtime-host 同做法）。
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
