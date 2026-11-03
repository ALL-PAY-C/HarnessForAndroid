plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.feature.chat"
}

dependencies {
    implementation(project(":design"))
    implementation(project(":render"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:protocol"))
    implementation(project(":plugin-api"))
    // 会话设置槽直接渲染插件组件（PluginSlotItem / rememberHostThemeTokens）。
    implementation(project(":plugin-host"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
