plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dsh.mobile.pluginhost"
    testOptions {
        unitTests {
            // NodeBackedPlugin 失败路径会经 AppLog -> android.util.Log.w 记一条告警；
            // JVM 单测的 mockable android.jar 里这些方法默认抛 "not mocked"，
            // 这里让桩方法返回默认值，避免真实的 stage/message 断言还没跑到就被日志打断。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":plugin-api"))
    // markdown 富内容节点复用宿主的块级渲染器。:render 只依赖 plugin-api/design/core，
    // 不反向依赖 plugin-host，所以不成环。
    implementation(project(":render"))
    // Node 插件（node:/@deepseek-ai/ 依赖）走 NodeEngineHost：plugin-host 只依赖它的
    // NodeEngine / NodeEngineException / LineProtocol 类型，不反向依赖 runtime-host 的 libnode。
    implementation(project(":runtime-host"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Rhino：纯 JVM 的 ES 引擎，无 native 依赖，Android API 26 上可直接跑装有 JS 入口的插件包。
    implementation("org.mozilla:rhino:1.7.15")
    testImplementation("junit:junit:4.13.2")
    // JVM 单测里 android.jar 的 org.json 是桩，用真实现替换才能验证 JSON 往返。
    testImplementation("org.json:json:20240303")
}
