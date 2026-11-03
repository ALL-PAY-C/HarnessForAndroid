plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.dsh.mobile.core.common"
}
dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
