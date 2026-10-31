plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.feature.sessions"
}

dependencies {
    api(project(":design"))
    api(project(":core:data"))
    api(project(":plugin-api"))
    // 会话列表页要画已放置的插件组件：PluginSlotItem / rememberHostThemeTokens 在 plugin-host。
    // plugin-host 只依赖 plugin-api/render/runtime-host，不反向依赖 feature/*，不成环。
    implementation(project(":plugin-host"))
    implementation("androidx.navigation:navigation-compose:2.8.5")
    testImplementation("junit:junit:4.13.2")
}
