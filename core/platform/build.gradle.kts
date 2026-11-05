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
    testImplementation(libs.junit)
}
