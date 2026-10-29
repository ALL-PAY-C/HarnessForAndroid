plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.feature.settings"
}

dependencies {
    api(project(":design"))
    api(project(":core:data"))
    api(project(":plugin-api"))
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation(project(":toolbridge"))
    testImplementation("junit:junit:4.13.2")
}
