plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.dsh.mobile.core.model"
}
dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
