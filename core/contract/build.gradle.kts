plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "dev.dsh.mobile.core.contract"
}
dependencies {
    // 契约层是最底层：只认 core:model 的协议数据模型与 kotlinx 原语，
    // 不引 core:platform / okhttp / core:download（否则又会把实现细节顶到底层）。
    // 包名沿用 dev.dsh.mobile.core.protocol.*：两个上层模块与消费方的 import 一行都不用改。
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
