plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.dsh.mobile.toolbridge"
}
dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // Shizuku：可选运行时依赖，未安装 Shizuku 时相关通道自动不可用
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    // ShellBridgeDispatchTest：runTest + StandardTestDispatcher（与 core/protocol、core/data 同版本）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
