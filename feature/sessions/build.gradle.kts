plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.feature.sessions"
}

dependencies {
    implementation(project(":design"))
    implementation(project(":core:data"))
    // SessionsViewModel.kt:111 以 DshTransport（core:protocol）作为类型出现。
    // 以前靠 plugin-host / terminal 的 api() 泄漏拿到，T1.2 降级为 implementation 后必须显式声明。
    implementation(project(":core:protocol"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":plugin-api"))
    // 会话列表页要画已放置的插件组件：PluginSlotItem / rememberHostThemeTokens 在 plugin-host。
    // plugin-host 只依赖 plugin-api/runtime-host，不反向依赖 feature/*，不成环。
    implementation(project(":plugin-host"))
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
}
