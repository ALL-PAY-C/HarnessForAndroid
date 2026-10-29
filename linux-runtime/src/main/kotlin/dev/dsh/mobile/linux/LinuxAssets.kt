package dev.dsh.mobile.linux

import android.content.res.AssetManager

/** 制品清单随 APK 走（assets/linux-artifacts.json），运行时不联网取清单。 */
object LinuxAssets {
    const val FILE = "linux-artifacts.json"

    fun load(assets: AssetManager): List<LinuxManifest> =
        assets.open(FILE).use { input -> LinuxArtifacts.parse(input.readBytes().decodeToString()) }

    fun forAbi(assets: AssetManager, abi: String): LinuxManifest? =
        LinuxArtifacts.forAbi(load(assets), abi)
}
