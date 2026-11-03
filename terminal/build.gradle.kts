plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dsh.mobile.terminal"
    // compileSdk / minSdk / JVM17 由根 build.gradle.kts 统一给。ndkVersion 是模块自己的事。
    ndkVersion = "26.3.11579264"

    defaultConfig {
        externalNativeBuild {
            ndkBuild {
                // Only the PTY helper. Everything else (emulator + renderer) is Kotlin.
                arguments += listOf("APP_PLATFORM=android-26")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    // 原本 api(:design) 属误挂：terminal 只有 com.termux.* / dev.dsh.mobile.terminal.*，
    // 对设计系统零引用（grep 0 命中）→ 去掉，vendored 代码一行没动（T1.2）。
    // 这两项刻意不进 catalog：catalog 里是多数模块用的 1.8.1 / 1.13.1，
    // terminal 需要更高的 1.9.1 / 1.15.0（Gradle 取高版本，其它模块的解析结果不变）。
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.15.0")
}
