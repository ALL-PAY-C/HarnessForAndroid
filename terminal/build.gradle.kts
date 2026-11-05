// vendored：本模块是第三方移植代码（com.termux.terminal / com.termux.view，源自 termux-app 终端模拟器），
// 23 个源文件 7,101 行里自研部分为 0；namespace dev.dsh.mobile.terminal 仅作为 AAR 标识存在。
// 约定：只接受编译级修补，不做重构、不按自研代码计入指标，改动须在提交说明与 README 台账里标注 vendored。
// 来源、禁改范围与升级流程见 README.md；O5 决策见 docs/REFACTOR-NOTES.md §五。
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

    testOptions {
        unitTests {
            // TerminalEmulator 只在 OSC 52 / 日志路径上碰 android.util.Base64 与 android.util.Log。
            // JVM 单测拿到的 android.jar 是桩，不打这个开关会抛 "not mocked"；返回默认值即可，
            // 这些调用不参与任何断言。
            isReturnDefaultValues = true
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

    // TerminalEmulatorTest：只吃 JVM（emulator / buffer / WcWidth 全是纯 Kotlin），不引 Robolectric。
    testImplementation(libs.junit)
}
