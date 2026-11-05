package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.IdentityCard
import dev.dsh.mobile.core.model.preset.AgentPreset
import dev.dsh.mobile.core.model.preset.CardImport
import dev.dsh.mobile.core.model.preset.ImportedCard

/**
 * 预设导入端口：由 core:data 的 AgentPresetImporter 实现。
 *
 * Zip/YAML 解析、卡片 JSON 编解码都是实现细节；UI 只经这 6 个成员进出。
 */
interface AgentPresetImportApi {
    fun importPreset(input: String): AgentPreset

    /** 单张身份卡 → 剪贴板文本。 */
    fun encodeCard(name: String, card: IdentityCard): String

    /** 全量身份卡 → 剪贴板文本。 */
    fun encodeAll(picked: List<Pair<String, IdentityCard>>): String

    /** 剪贴板文本 → 卡片（整段坏掉给 Failed，单张坏掉记进 skipped）。 */
    fun decode(text: String): CardImport

    /** 落库前的 id 分配：与 taken 撞车就依次试 -2、-3…，绝不覆盖已有预设。 */
    fun toPresets(cards: List<ImportedCard>, taken: Set<String>): List<AgentPreset>

    /** 一次导入的卡片张数上限（实现侧常量，UI 用来提前拦）。 */
    val maxCards: Int
}
