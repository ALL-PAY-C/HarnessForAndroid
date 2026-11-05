plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.dsh.mobile.core.platform"
}
dependencies {
    // AppScope / HttpJson 用到 Android 主线程调度器；核心线程原语 Kotlin 自带。
    implementation(libs.kotlinx.coroutines.android)
    // S1: DshResult 迁入 core:model 后 platform 侧仍有同名包消费方，故显式暴露。
    api(project(":core:model"))
    testImplementation(libs.junit)
    // ContextWindowTest 走 parseContextLengths（org.json 在单测里是桩，需真实现）。
    testImplementation(libs.org.json)
}
