plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.feature.plugins"
}

dependencies {
    implementation(project(":design"))
    implementation(project(":core:model"))
    implementation(project(":plugin-api"))
    implementation(project(":plugin-host"))
    // 解包统一走 core:download 的 ZipExtract（不再各模块自带 zip-slip 守卫）。
    implementation(project(":core:platform"))
    implementation(project(":core:download"))
    implementation(project(":runtime-host"))
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    // 桌面 JVM 上 org.json 只有桩实现，单测里换成真实现（与 plugin-host 同做法）。
    testImplementation(libs.org.json)
}
