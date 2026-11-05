plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.render"
}

dependencies {
    // plugin-api 只被 RichText.kt 用来读 HtmlBlockRenderers/DiagramRenderers 槽位（实现细节，
    // 不出现在任何公共签名里）→ implementation。:design / :core:model / :core:platform
    // 本模块零引用（grep 0 命中），整条去掉：渲染层不再反向依赖设计系统。
    implementation(project(":plugin-api"))

    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.compose.ui)
    api(libs.compose.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.preview)
    debugApi(libs.compose.tooling)
    testImplementation(libs.junit)
}
