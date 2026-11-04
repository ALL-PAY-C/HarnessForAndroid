package dev.dsh.mobile.design

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.IdentityCard

/**
 * 身份卡编辑区：八个字段 + 预览 + 清空，全部用现有 OutlinedTextField 范式与调色板。
 *
 * 列表字段（职责/禁区/准则/示例）在 UI 里是「一行一条」的多行文本，保存时才拆成 List，
 * 这样敲换行不会被立刻吃掉。预览直接渲染卡本体，所见即注入内容。
 *
 * 放在 design 模块是为了让「预设身份卡」（feature:chat 抽屉）与「群聊角色卡」
 * （feature:settings 群聊区）共用同一套编辑器，不复制第二份表单。
 */
@Composable
fun QqIdentityCardEditor(
    initial: IdentityCard,
    busy: Boolean,
    onSave: (IdentityCard?) -> Unit,
    legacyPersona: String = "",
    onAdopt: () -> Unit = {},
    /** 卡下方的一行说明（内置预设/角色卡的存储位置），null 不渲染。 */
    hint: String? = null,
    /** 清空按钮文案：预设卡是「清空」，角色卡是「恢复默认」；null 用默认文案。 */
    clearLabel: String? = null,
    clearHint: String? = null,
) {
    val p = LocalQq.current
    // 换预设 / 换角色时整块重建：remember 的 key 是 initial，展开另一张卡不会串内容。
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
    val clearText = clearLabel ?: stringResource(R.string.common_clear)
    val clearHintText = clearHint ?: stringResource(R.string.design_identity_clear_hint)

    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QqCardField(stringResource(R.string.design_identity_field_name), name, { name = it }, singleLine = true)
        QqCardField(stringResource(R.string.design_identity_field_identity), identity, { identity = it })
        QqCardField(stringResource(R.string.design_identity_field_duties), duties, { duties = it })
        QqCardField(stringResource(R.string.design_identity_field_boundaries), boundaries, { boundaries = it })
        QqCardField(stringResource(R.string.design_identity_field_rules), rules, { rules = it })
        QqCardField(stringResource(R.string.design_identity_field_tone), tone, { tone = it }, singleLine = true)
        QqCardField(stringResource(R.string.design_identity_field_examples), examples, { examples = it })
        QqCardField(stringResource(R.string.design_identity_field_freeform), freeform, { freeform = it })

        Text(stringResource(R.string.common_preview), color = p.t3, fontSize = 11.sp)
        Text(
            preview.ifBlank { stringResource(R.string.design_identity_empty_preview) },
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
                TextButton(enabled = !busy, onClick = onAdopt) { Text(stringResource(R.string.design_identity_adopt), fontSize = 12.sp) }
            }
            TextButton(enabled = !busy, onClick = { onSave(null) }) { Text(clearText, color = p.red, fontSize = 12.sp) }
            TextButton(enabled = !busy && current.hasContent(), onClick = { onSave(current) }) {
                Text(stringResource(if (busy) R.string.design_identity_saving else R.string.common_save), fontSize = 12.sp)
            }
        }
        Text(clearHintText, color = p.t3, fontSize = 10.sp)
        hint?.let { Text(it, color = p.t3, fontSize = 10.sp) }
    }
}

@Composable
private fun QqCardField(
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
