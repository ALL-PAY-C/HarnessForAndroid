pluginManagement {
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
    ":feature:sessions",
    ":feature:chat",
    ":feature:agents",
    ":feature:settings",
    ":feature:plugins",
)
