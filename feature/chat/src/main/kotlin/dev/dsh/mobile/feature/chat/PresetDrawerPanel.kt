package dev.dsh.mobile.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.core.model.preset.AgentPreset
import dev.dsh.mobile.core.model.preset.CardError
import dev.dsh.mobile.core.model.IdentityCard
import dev.dsh.mobile.core.model.preset.IdentityCards
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqIdentityCardEditor
import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.text
import kotlinx.coroutines.launch

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
    // 剪贴板读写留在 UI 层：VM 不持 Context，导出的 JSON 由这里写进系统剪贴板。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun copyToClipboard(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("dsh-identity-card", text))
    }
    fun clipboardText(): String {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    }

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
                    if (state.sessionScoped) stringResource(DesignR.string.preset_scope_session, state.defaultName)
                    else stringResource(DesignR.string.preset_scope_default, state.defaultName),
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
            ) { Text(stringResource(if (importing) DesignR.string.common_collapse else DesignR.string.preset_import)) }
        }

        state.error?.let { message ->
            Text(
                message.text(),
                color = p.red,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.red.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        state.notice?.let { notice ->
            Text(
                when (notice) {
                    is CardNotice.Copied -> stringResource(DesignR.string.preset_card_copied, notice.count)
                    CardNotice.NothingToExport -> stringResource(DesignR.string.preset_card_export_empty)
                    is CardNotice.Imported ->
                        if (notice.skipped.isEmpty()) {
                            stringResource(DesignR.string.preset_card_import_done, notice.cards)
                        } else {
                            stringResource(
                                DesignR.string.preset_card_import_done_skipped,
                                notice.cards,
                                notice.skipped.size,
                                stringResource(skipReasonRes(notice.skipped.first().reason)),
                            )
                        }
                },
                color = p.accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.accent.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        // 身份卡导入导出：两张导出（当前 / 全部）+ 一张从剪贴板导入。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = !state.busy,
                onClick = { scope.launch { viewModel.requestExport(all = false)?.let { copyToClipboard(it) } } },
            ) { Text(stringResource(DesignR.string.preset_card_export_current), fontSize = 11.5.sp) }
            TextButton(
                enabled = !state.busy,
                onClick = { scope.launch { viewModel.requestExport(all = true)?.let { copyToClipboard(it) } } },
            ) { Text(stringResource(DesignR.string.preset_card_export_all), fontSize = 11.5.sp) }
            TextButton(
                enabled = !state.busy,
                onClick = { viewModel.importCards(clipboardText()) },
            ) { Text(stringResource(DesignR.string.preset_card_import_clip), fontSize = 11.5.sp) }
        }

        if (importing) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    viewModel.clearError()
                },
                label = { Text(stringResource(DesignR.string.preset_import_placeholder)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { importing = false }) { Text(stringResource(DesignR.string.common_cancel)) }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    enabled = !state.busy && input.isNotBlank(),
                    onClick = { viewModel.import(input) },
                ) { Text(stringResource(if (state.busy) DesignR.string.preset_importing else DesignR.string.preset_import_apply)) }
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
                                stringResource(
                                    DesignR.string.preset_card_label,
                                    card.name.ifBlank { stringResource(DesignR.string.preset_card_unnamed) },
                                ),
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
                    ) { Text(stringResource(if (editing == preset.id) DesignR.string.common_collapse else DesignR.string.preset_card), fontSize = 12.sp) }
                    // 内置预设不可删（Store 会拒绝），导入的才给删除入口。
                    if (!preset.builtin) {
                        TextButton(enabled = !state.busy, onClick = { viewModel.delete(preset.id) }) {
                            Text(stringResource(DesignR.string.common_delete), color = p.red, fontSize = 12.sp)
                        }
                    }
                }
                if (editing == preset.id) {
                    // 编辑器已上移到 design（IdentityCardUi.kt），与群聊角色卡共用同一份表单。
                    QqIdentityCardEditor(
                        initial = card ?: IdentityCard(),
                        busy = state.busy,
                        onSave = { viewModel.saveCard(preset.id, it) },
                        legacyPersona = if (selected) state.legacyPersona else "",
                        onAdopt = { viewModel.adoptLegacyPersona(preset, state.legacyPersona) },
                        hint = if (preset.builtin) stringResource(DesignR.string.preset_card_note) else null,
                    )
                }
            }
        }
    }
}

/** 单张卡被跳过时的短原因，拼进「导入 N 张，跳过 M 张（原因）」。 */
private fun skipReasonRes(reason: CardError): Int = when (reason) {
    CardError.MissingName -> DesignR.string.card_skip_missing_name
    CardError.TooLong -> DesignR.string.card_skip_too_long
    CardError.BadType -> DesignR.string.card_skip_bad_type
    CardError.TooManyCards -> DesignR.string.card_skip_too_many
    CardError.EmptyCards -> DesignR.string.card_skip_empty
    CardError.Blank, CardError.BadJson, CardError.Shape, CardError.UnsupportedSchema ->
        DesignR.string.card_skip_bad_json
}

