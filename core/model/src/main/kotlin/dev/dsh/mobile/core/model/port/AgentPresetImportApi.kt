package dev.dsh.mobile.core.model.port

import dev.dsh.mobile.core.model.preset.AgentPreset

/** 预设导入端口：由 core:data 的 AgentPresetImporter 实现。importZip / parseYaml 是实现细节。 */
interface AgentPresetImportApi {
    fun importPreset(input: String): AgentPreset
}
