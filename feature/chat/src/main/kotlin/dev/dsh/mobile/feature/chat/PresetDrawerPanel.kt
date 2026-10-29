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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.card)
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
                    if (preset.source.isNotBlank()) {
                        Text(preset.source, color = p.t3, fontSize = 10.sp, maxLines = 1)
                    }
                }
                // 内置预设不可删（Store 会拒绝），导入的才给删除入口。
                if (!preset.builtin) {
                    TextButton(enabled = !state.busy, onClick = { viewModel.delete(preset.id) }) {
                        Text("删除", color = p.red, fontSize = 12.sp)
                    }
                } else {
                    Text("内置", color = p.t3, fontSize = 11.sp)
                }
            }
        }
    }
}
