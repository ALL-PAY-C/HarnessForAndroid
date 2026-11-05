plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.feature.agents"
}

dependencies {
    // feature 层是叶子：被 app 单向使用，没有任何下游模块需要这些类型，一律 implementation。
    // :terminal 必须留着 —— TerminalScreen.kt 直接 import com.termux.terminal.* / com.termux.view.*
    // （vendored termux 的包名不是 dev.dsh.mobile.terminal，按包名 grep 会漏），
    // 但它是纯 UI 实现细节，:app 只需要 feature:agents 的 Composable，所以 api → implementation。
    implementation(project(":terminal"))
    implementation(project(":toolbridge"))
    implementation(project(":design"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:platform"))
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    // 桌面 JVM 上 org.json 只有桩实现，单测里换成真实现（与 plugin-host 同做法）。
    testImplementation(libs.org.json)
}
