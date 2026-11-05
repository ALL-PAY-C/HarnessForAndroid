plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "dev.dsh.mobile.core.transport"
    // 与 core:data 同因：JVM 单测里 android.util.Log 是桩，AppLog.w/e 一调用就抛「not mocked」
    // （AcpTransport / IdentityInjector 的失败收尾路径必然打日志）。
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    // 包名沿用 dev.dsh.mobile.core.protocol.*：本模块只是 gradle 层面的传输层，
    // 上层（core:protocol 及其消费方）的 import 一行都不用改。
    // 契约层（DshTransport / LineChannel / LlmConfig / DshJson）独立成 :core:contract，
    // 它们的类型出现在本模块公共签名里（AcpTransport : DshTransport 等）→ api。
    api(project(":core:contract"))
    api(project(":core:model"))
    // AppLog / LoopbackGateway 只在实现里用，公共签名不出现它们的类型 → implementation。
    implementation(project(":core:platform"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    // WebSocketChannel 的构造签名带 OkHttpClient 类型参数，属公共 ABI → api。
    api(libs.okhttp)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
