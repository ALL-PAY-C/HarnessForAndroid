package dev.dsh.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 空实现：apply 它只是为了让模块的 .gradle.kts 脚本能引用本 build 编译出的
 * [DownloadVerifiedArtifact]、[Mirrors]、[Hashes]（插件被应用后这些类进入脚本 classpath）。
 */
class DshBuildLogicPlugin : Plugin<Project> {
    override fun apply(target: Project) = Unit
}
