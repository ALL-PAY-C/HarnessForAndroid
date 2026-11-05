plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.dsh.mobile.core.download"
}
dependencies {
    // 分层（O3）：download 单向依赖 platform，禁止反向；代码目前未直接引用 platform 符号，
    // 这条边是依赖方向约束本身，不是编译需要。
    implementation(project(":core:platform"))
    // SegmentedDownloader 的分片并发 / 超时取消。
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
