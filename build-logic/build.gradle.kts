plugins {
    `kotlin-dsl`
}

// 只用 Gradle API + Kotlin 标准库；gradlePlugin 只为了让模块脚本能把这里编译出的
// 任务类型放进自己的 script classpath（插件本体是空实现）。
gradlePlugin {
    plugins {
        register("dshBuildLogic") {
            id = "dsh-build-logic"
            implementationClass = "dev.dsh.buildlogic.DshBuildLogicPlugin"
        }
    }
}
