plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.dsh.mobile.core.model"
}
dependencies {
    api(libs.kotlinx.coroutines.core)
    // IdentityCard / GroupLedger 都是 @Serializable：序列化插件与 json 跟着模型走，
    // 否则 core:data 与 core:agent 解析同一种落盘格式会得出两套定义。
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
