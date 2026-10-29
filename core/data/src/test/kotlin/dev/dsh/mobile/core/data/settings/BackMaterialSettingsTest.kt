package dev.dsh.mobile.core.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.dsh.mobile.core.model.BackMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 材质读写与旧键迁移。
 *
 * 这两条路径以前只会「在真机上点一遍」才被验到，回归一次就得重装一次 APK；
 * 现在读取（[resolveBackMaterial]）与写入（[writeBackMaterial]）都是吃 Preferences 的纯函数。
 */
class BackMaterialSettingsTest {

    @Test
    fun freshInstallDefaultsToSolid() {
        // L2 卡面统一实底之后，全新安装的默认是 none（实底），玻璃是选项。
        assertEquals(BackMaterial.NONE, resolveBackMaterial(emptyPreferences()))
        assertEquals(BackMaterial.NONE, AppSettings().material)
        assertEquals("none", AppSettings().backMaterial)
    }

    @Test
    fun legacyGlassEnabledTrueMigratesToMatte() {
        val p = mutablePreferencesOf(SettingsStore.K.glassEnabled to true)
        assertEquals(BackMaterial.MATTE, resolveBackMaterial(p))
    }

    @Test
    fun legacyGlassEnabledFalseMigratesToNone() {
        val p = mutablePreferencesOf(SettingsStore.K.glassEnabled to false)
        assertEquals(BackMaterial.NONE, resolveBackMaterial(p))
    }

    @Test
    fun newKeyWinsOverLegacyKey() {
        val p = mutablePreferencesOf(
            SettingsStore.K.backMaterial to "liquid",
            SettingsStore.K.glassEnabled to false,
        )
        assertEquals(BackMaterial.LIQUID, resolveBackMaterial(p))
    }

    @Test
    fun storedMaterialSurvivesRoundTrip() {
        BackMaterial.entries.forEach { material ->
            val p = mutablePreferencesOf()
            writeBackMaterial(p, material.key)
            assertEquals(material, resolveBackMaterial(p))
            // 旧键是镜像：玻璃与否必须和新材质一致，降级回 v0.05 才不会突然变成实底。
            assertEquals(material.isGlass, p[SettingsStore.K.glassEnabled])
        }
    }

    @Test
    fun dirtyStoredValueFallsBackToDefault() {
        val p = mutablePreferencesOf(SettingsStore.K.backMaterial to "glass")
        assertEquals(BackMaterial.DEFAULT, resolveBackMaterial(p))
        // 大小写与空格都能认出，不要求调用方自己规范化。
        assertEquals(BackMaterial.ACRYLIC, BackMaterial.parse("  ACRYLIC "))
        assertEquals(BackMaterial.LIQUID, BackMaterial.parse("liquid"))
    }

    @Test
    fun noneIsWrittenAsNonGlass() {
        val p = mutablePreferencesOf()
        writeBackMaterial(p, BackMaterial.NONE.key)
        assertEquals("none", p[SettingsStore.K.backMaterial])
        assertFalse(p[SettingsStore.K.glassEnabled]!!)
        assertEquals(BackMaterial.NONE, resolveBackMaterial(p))
    }

    @Test
    fun glassEnabledGetterIsDerivedFromBackMaterial() {
        assertTrue(AppSettings(backMaterial = "acrylic").glassEnabled)
        assertFalse(AppSettings(backMaterial = "none").glassEnabled)
        assertEquals(BackMaterial.LIQUID, AppSettings(backMaterial = "liquid").material)
    }
}
