plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.feature.plugins"
}

dependencies {
    api(project(":design"))
    api(project(":core:data"))
    api(project(":plugin-api"))
    api(project(":plugin-host"))
    implementation(project(":runtime-host"))
    implementation("androidx.navigation:navigation-compose:2.8.5")
    testImplementation("junit:junit:4.13.2")
    // 桌面 JVM 上 org.json 只有桩实现，单测里换成真实现（与 plugin-host 同做法）。
    testImplementation("org.json:json:20240303")
}
