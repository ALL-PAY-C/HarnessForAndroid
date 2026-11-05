plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.dsh.mobile.toolbridge"
}
dependencies {
    // 公共签名全是本模块自己的 BridgeResult / Channel 类型；platform 只用 AppLog/ProxyEnv，
    // download 只用 PendingImageQueue（都只在实现内部）→ implementation。core:model 零引用，去掉。
    implementation(project(":core:platform"))
    implementation(project(":core:download"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    // Shizuku：可选运行时依赖，未安装 Shizuku 时相关通道自动不可用
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    // PhoneTreeReadTest：PhoneSelector.parse 吃 JSONObject，JVM 单测必须用真实现（AGP 的 android.jar 是桩，会报 not mocked）
    testImplementation(libs.org.json)
    // ShellBridgeDispatchTest：runTest + StandardTestDispatcher（与 core/protocol、core/data 同版本）
    testImplementation(libs.kotlinx.coroutines.test)
}
