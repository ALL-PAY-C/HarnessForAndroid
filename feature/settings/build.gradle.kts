plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.feature.settings"
}

dependencies {
    implementation(project(":design"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:platform"))
    implementation(project(":plugin-api"))
    implementation(libs.androidx.navigation.compose)
    implementation(project(":toolbridge"))
    testImplementation(libs.junit)
}
