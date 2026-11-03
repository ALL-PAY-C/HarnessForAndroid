package dev.dsh.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.core.data.preset.AgentPreset
import dev.dsh.mobile.core.data.preset.IdentityCard
import dev.dsh.mobile.core.data.preset.IdentityCards
import dev.dsh.mobile.design.LocalQq

/**
 * 会话页左侧抽屉里的「Agent 预设」面板：列预设 + 设默认 + 导入 + 删除。
 * 导入失败不弹一闪而过的提示，直接在面板顶部留一行红字，直到下一次操作。
 */
@Composable
fun PresetDrawerPanel(viewModel: PresetDrawerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val p = LocalQq.current
    var importing by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    /** 同时只展开一个预设的身份卡编辑区，避免一屏八个表单。 */
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // 标题已经在外壳顶栏上，这里只报当前默认是谁，免得同一页出现两行同名。
                Text(
                    (if (state.sessionScoped) "本会话：" else "默认：") + state.defaultName,
                    color = p.t2,
                    fontSize = 12.5.sp,
                )
            }
            TextButton(
                enabled = !state.busy,
                onClick = {
                    importing = !importing
                    viewModel.clearError()
                },
            ) { Text(if (importing) "收起" else "导入") }
        }

        state.error?.let { message ->
            Text(
                message,
                color = p.red,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.red.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        if (importing) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    viewModel.clearError()
                },
                label = { Text("GitHub owner/repo、ZIP/JSON 链接、本地路径或 JSON 文本") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { importing = false }) { Text("取消") }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    enabled = !state.busy && input.isNotBlank(),
                    onClick = { viewModel.import(input) },
                ) { Text(if (state.busy) "导入中…" else "导入并设为默认") }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))

        state.presets.forEach { preset ->
            val selected = preset.id == state.defaultPresetId
            // 覆盖表里的卡优先，其次是预设自带卡，最后才是会话里那个旧 persona 合成的只读卡。
            val card = IdentityCards.ofCard(
                state.cards[preset.id] ?: preset.card,
                if (selected) state.legacyPersona else "",
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.card),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setDefault(preset.id) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { viewModel.setDefault(preset.id) })
                    Column(Modifier.weight(1f).padding(end = 6.dp)) {
                        Text(preset.name, color = p.t1, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (preset.description.isNotBlank()) {
                            Text(preset.description, color = p.t3, fontSize = 11.sp)
                        }
                        if (card != null) {
                            Text(
                                "身份卡：" + card.name.ifBlank { "未命名" },
                                color = p.accent,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (preset.source.isNotBlank()) {
                            Text(
                                preset.source,
                                color = p.t3,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        enabled = !state.busy,
                        onClick = {
                            editing = if (editing == preset.id) null else preset.id
                            viewModel.clearError()
                        },
                    ) { Text(if (editing == preset.id) "收起" else "身份卡", fontSize = 12.sp) }
                    // 内置预设不可删（Store 会拒绝），导入的才给删除入口。
                    if (!preset.builtin) {
                        TextButton(enabled = !state.busy, onClick = { viewModel.delete(preset.id) }) {
                            Text("删除", color = p.red, fontSize = 12.sp)
                        }
                    }
                }
                if (editing == preset.id) {
                    IdentityCardEditor(
                        preset = preset,
                        initial = card ?: IdentityCard(),
                        legacyPersona = if (selected) state.legacyPersona else "",
                        busy = state.busy,
                        onSave = { viewModel.saveCard(preset.id, it) },
                        onAdopt = { viewModel.adoptLegacyPersona(preset, state.legacyPersona) },
                    )
                }
            }
        }
    }
}

/**
 * 身份卡编辑区：八个字段 + 预览 + 清空，全部用现有 OutlinedTextField 范式与调色板。
 *
 * 列表字段（职责/禁区/准则/示例）在 UI 里是「一行一条」的多行文本，保存时才拆成 List，
 * 这样敲换行不会被立刻吃掉。预览直接渲染卡本体，所见即注入内容。
 */
@Composable
private fun IdentityCardEditor(
    preset: AgentPreset,
    initial: IdentityCard,
    legacyPersona: String,
    busy: Boolean,
    onSave: (IdentityCard?) -> Unit,
    onAdopt: () -> Unit,
) {
    val p = LocalQq.current
    // 换预设时整块重建：remember 的 key 是 initial，展开另一张卡不会串内容。
    var name by remember(initial) { mutableStateOf(initial.name) }
    var identity by remember(initial) { mutableStateOf(initial.identity) }
    var duties by remember(initial) { mutableStateOf(initial.duties.joinToString("\n")) }
    var boundaries by remember(initial) { mutableStateOf(initial.boundaries.joinToString("\n")) }
    var rules by remember(initial) { mutableStateOf(initial.rules.joinToString("\n")) }
    var tone by remember(initial) { mutableStateOf(initial.tone) }
    var examples by remember(initial) { mutableStateOf(initial.examples.joinToString("\n")) }
    var freeform by remember(initial) { mutableStateOf(initial.freeform) }

    val current = IdentityCard(
        name = name.trim(),
        identity = identity.trim(),
        duties = duties.toItems(),
        boundaries = boundaries.toItems(),
        rules = rules.toItems(),
        tone = tone.trim(),
        examples = examples.toItems(),
        freeform = freeform.trim(),
    )
    val preview = current.render()

    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardField("卡名", name, { name = it }, singleLine = true)
        CardField("你是谁（一句话身份）", identity, { identity = it })
        CardField("职责（一行一条）", duties, { duties = it })
        CardField("禁区（一行一条，优先级最高）", boundaries, { boundaries = it })
        CardField("准则（一行一条）", rules, { rules = it })
        CardField("口吻", tone, { tone = it }, singleLine = true)
        CardField("示例（一行一条）", examples, { examples = it })
        CardField("自由补充", freeform, { freeform = it })

        Text("预览", color = p.t3, fontSize = 11.sp)
        Text(
            preview.ifBlank { "（空卡：保存后不注入任何内容）" },
            color = p.t2,
            fontSize = 11.5.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.t3.copy(alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (legacyPersona.isNotBlank() && current.freeform.isBlank() && initial.freeform.isBlank()) {
                TextButton(enabled = !busy, onClick = onAdopt) { Text("转为身份卡", fontSize = 12.sp) }
            }
            TextButton(enabled = !busy, onClick = { onSave(null) }) { Text("清空", color = p.red, fontSize = 12.sp) }
            TextButton(enabled = !busy && current.hasContent(), onClick = { onSave(current) }) {
                Text(if (busy) "保存中…" else "保存", fontSize = 12.sp)
            }
        }
        Text(
            "清空只清这张卡；会话里已有的旧人设文本保持不动，会重新作为只读卡出现。",
            color = p.t3,
            fontSize = 10.sp,
        )
        if (preset.builtin) {
            Text("内置预设的卡存在本机覆盖层里，不修改内置本体。", color = p.t3, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CardField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = singleLine,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (singleLine) Modifier else Modifier.heightIn(min = 72.dp, max = 160.dp)),
    )
}

/** 多行文本 → 列表：去掉空行与首尾空白，顺序保持用户敲的顺序。 */
private fun String.toItems(): List<String> =
    splitToSequence('\n').map { it.trim() }.filter { it.isNotEmpty() }.toList()

