plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.design"
}

dependencies {
    api(project(":core:model"))
    // core:common 被 Components.kt:535/:685 以全限定名 dev.dsh.mobile.core.common.TimeFmt 使用，
    // 不是 import 形式，grep "import dev.dsh.mobile.core.common" 会漏判。保持 api 以维持下游可见性。
    api(project(":core:common"))

    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.compose.ui)
    api(libs.compose.graphics)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.icons)
    api(libs.compose.preview)
    debugApi(libs.compose.tooling)
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
