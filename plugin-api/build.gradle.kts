plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.pluginapi"
}

dependencies {
    // 全模块唯一保留的 project api()：Plugin 接口与数据类的公共签名直接用 core.model 的
    // AgentProfile / ChatMessage / Session，插件侧编译必须看得见这些类型。
    // core:common 零引用，去掉。
    api(project(":core:model"))
    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    testImplementation(libs.junit)
}
