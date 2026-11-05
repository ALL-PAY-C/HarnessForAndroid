plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "dev.dsh.mobile.core.protocol"
}
dependencies {
    // 引擎层（原 :core:protocol，S3b 迁入 :core:agent；包名不变）：传输/流解析层已下沉到 :core:transport，
    // 本模块源码与公共 API 都直接引用这些类型，消费方（core:data / feature / app）也按包名 import 它们 → api。
    api(project(":core:transport"))
    // 引擎层直接 import 契约符号（DshTransport / LlmConfig / DshJson / parseSubagentTasks），
    // 不能只靠 :core:transport 传递 → 显式 api。
    api(project(":core:contract"))
    api(project(":core:model"))
    // platform（AppLog / DeviceCapability）与 download（PendingImageQueue）都只在实现里用，
    // 公共签名（LineChannel / 协议模型）不出现它们的类型 → implementation。
    implementation(project(":core:platform"))
    implementation(project(":core:download"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证引擎 RPC 的 JSON 往返
    // （与 core:data / plugin-host / runtime-host 同做法）。
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
