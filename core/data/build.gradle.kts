plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.dsh.mobile.core.data"
    // JVM 单测里 android.util.Log 是桩，AppLog.w/e 一调用就抛「not mocked」——
    // 失败收尾路径必然打日志，不给默认值就测不到那些分支。
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(project(":core:protocol"))

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    api(libs.androidx.datastore.preferences)
    // 自更新要 FileProvider 把 APK 交给系统安装器
    api(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证 JSON 往返（与 plugin-host 同做法）。
    testImplementation(libs.org.json)
}
