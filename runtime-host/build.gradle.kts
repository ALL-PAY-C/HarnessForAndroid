plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dsh.mobile.runtimehost"
}

dependencies {
    api(project(":core:protocol"))
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":node-runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证 JSON 往返。
    testImplementation("org.json:json:20240303")
}
