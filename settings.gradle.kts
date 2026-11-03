pluginManagement {
    // build-logic 是 included build：两处 runtime 模块的下载/校验任务类型与镜像列表都来自它。
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-mobile"

include(
    ":app",
    ":node-runtime",
    ":core:common",
    ":core:model",
    ":core:protocol",
    ":core:data",
    ":design",
    ":render",
    ":runtime-host",
    ":toolbridge",
    ":linux-runtime",
    ":terminal",
    ":plugin-api",
    ":plugin-host",
    ":plugin-html",
    ":plugin-html-apk",
    ":feature:sessions",
    ":feature:chat",
    ":feature:agents",
    ":feature:settings",
    ":feature:plugins",
)
