plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.dsh.mobile.core.data"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:protocol"))

    api("androidx.room:room-runtime:2.6.1")
    api("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    api("androidx.datastore:datastore-preferences:1.1.1")
    // 自更新要 FileProvider 把 APK 交给系统安装器
    api("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证 JSON 往返（与 plugin-host 同做法）。
    testImplementation("org.json:json:20240303")
}
