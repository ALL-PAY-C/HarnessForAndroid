package dev.dsh.mobile.core.model

/**
 * 面板材质。v0.05 只有一个「磨砂玻璃」bool，表达不了液态 / 亚克力，也表达不了「关掉后
 * 页面要实底」——三种观感全被压进同一个 alpha，切换时用户看不出差别。
 *
 * 落盘只存 [key]（preferences 键 `back_material`），旧键 `glass_enabled` 的迁移见 [migrate]。
 * 这个枚举放在 core:model：core:data 负责读写、design 负责出图，两边都看得见它。
 */
enum class BackMaterial(val key: String) {
    /** 无材质：页面与面板都是实色，壁纸不参与绘制。 */
    NONE("none"),

    /** 磨砂：v0.05 开玻璃时的观感——半透明面板 + 背景雾化。 */
    MATTE("matte"),

    /** 液态玻璃：更透 + 顶部高光 + 边缘折射，自带轻微模糊。 */
    LIQUID("liquid"),

    /** 亚克力：高不透明度 + 细噪点，重磨砂。 */
    ACRYLIC("acrylic"),
    ;

    /** 面板是不是半透明（= 旧 glassEnabled 的语义）。 */
    val isGlass: Boolean get() = this != NONE

    companion object {
        /** 全新安装的默认材质：实底。玻璃是选项，不该是默认观感。 */
        val DEFAULT = NONE

        /** 面板不透明度下限：再低正文压在壁纸上读不出来。滑块与读取共用这一个数。 */
        const val MIN_ALPHA = 0.45f
        const val MAX_ALPHA = 1.0f

        /** 认不出来的值一律回落到 [DEFAULT]，不让一处脏数据把整个外观打回纯色。 */
        fun parse(raw: String?): BackMaterial =
            entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT

        fun clampStrength(v: Float): Float = v.coerceIn(MIN_ALPHA, MAX_ALPHA)

        /**
         * 读 `back_material`，读不到再按旧键 `glass_enabled` 迁移：
         * - 新键有值：用它；
         * - 旧键 true：磨砂（老用户开过玻璃，观感保持）；
         * - 旧键 false：无材质（老用户明确关过，不给他凭空开玻璃）；
         * - 两个键都没有：全新安装 → [DEFAULT]（实底）。
         */
        fun migrate(stored: String?, legacyGlassEnabled: Boolean?): BackMaterial = when {
            !stored.isNullOrBlank() -> parse(stored)
            legacyGlassEnabled == true -> MATTE
            legacyGlassEnabled == false -> NONE
            else -> DEFAULT
        }
    }
}
