plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.pluginapi"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    api(composeBom)
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
