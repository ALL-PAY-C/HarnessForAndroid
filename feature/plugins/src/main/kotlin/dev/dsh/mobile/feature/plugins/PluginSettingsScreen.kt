package dev.dsh.mobile.feature.plugins

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.design.GroupChild
import dev.dsh.mobile.design.GroupFrame
import dev.dsh.mobile.design.GroupHeader
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.pluginapi.ConfigField
import dev.dsh.mobile.pluginapi.ConfigFieldType
import dev.dsh.mobile.pluginapi.SlotAction
import dev.dsh.mobile.pluginhost.PluginSlotItem
import dev.dsh.mobile.pluginhost.rememberHostThemeTokens

/**
 * 单个插件的设置详情页。
 *
 * 三块内容按「先能操作、再是插件自绘界面、最后才是外链」排序：
 * 用户点进来是为了改设置，声明式 ui 是插件的补充界面，外链只是兜底。
 */
@Composable
internal fun PluginSettingsDetail(
    entry: PluginSettingsEntry,
    onSave: (key: String, value: String) -> Unit,
    onAction: (SlotAction) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val p = LocalQq.current
    val page = entry.page
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IdentityCard(entry)

        entry.failureText?.let { failure ->
            GroupFrame {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                    Text(failure, color = p.accent2, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "插件代码没能加载，下面只剩声明式部分。检查插件目录或重新安装该插件。",
                        color = p.t3,
                        fontSize = 11.5.sp,
                    )
                }
            }
        }

        if (entry.fields.isNotEmpty()) {
            GroupFrame {
                GroupHeader(label = "设置项", count = entry.fields.size, collapsible = false)
                Column(Modifier.padding(vertical = 4.dp)) {
                    entry.fields.forEach { field ->
                        ConfigFieldRow(field, entry.valueOf(field)) { value -> onSave(field.key, value) }
                    }
                }
            }
        }

        if (page != null && entry.hasPage) {
            Text(
                "插件界面",
                color = p.t3,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
            GroupFrame {
                Column(Modifier.padding(10.dp)) {
                    PluginSlotItem(page, rememberHostThemeTokens(), onAction)
                }
            }
        }

        val url = entry.webUrl
        if (url != null) {
            GroupFrame {
                GroupChild(onClick = { onOpenUrl(url) }) {
                    Text(
                        "在浏览器打开",
                        color = p.t1,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text("↗", color = p.t3, fontSize = 14.sp)
                }
            }
        }

        entry.emptyText?.let { empty ->
            GroupFrame {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 14.dp)) {
                    Text(empty, color = p.t2, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(NO_SETTINGS_HINT, color = p.t3, fontSize = 11.5.sp)
                }
            }
        }
    }
}

/** 插件身份卡：名称、版本、说明、包名。用户要确认「我改的是哪个插件」全靠这块。 */
@Composable
private fun IdentityCard(entry: PluginSettingsEntry) {
    val p = LocalQq.current
    GroupFrame {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    color = p.t1,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("v" + entry.version, color = p.t3, fontSize = 11.5.sp)
            }
            if (entry.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(entry.description, color = p.t3, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.pluginId, color = p.t3, fontSize = 11.sp)
        }
    }
}

/** 一行设置项：开关单独一行，其余字段用「标签 + 输入框」两段式。 */
@Composable
private fun ConfigFieldRow(field: ConfigField, current: String, onSave: (String) -> Unit) {
    val p = LocalQq.current
    if (field.type == ConfigFieldType.Switch) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(field.label, color = p.t1, fontSize = 15.sp)
                if (field.hint.isNotBlank()) {
                    Text(field.hint, color = p.t3, fontSize = 11.5.sp)
                }
            }
            // 同 ValueBox：写回存储后列表快照不会重组，开关状态要留在本地才即时可见。
            var on by remember(field.key, current) { mutableStateOf(switchOn(current)) }
            Switch(checked = on, onCheckedChange = { on = it; onSave(it.toString()) })
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp)) {
        Text(field.label, color = p.t1, fontSize = 15.sp)
        if (field.hint.isNotBlank()) {
            Text(field.hint, color = p.t3, fontSize = 11.5.sp)
        }
        Spacer(Modifier.height(6.dp))
        if (field.type == ConfigFieldType.Select) {
            SelectBox(field, current, onSave)
        } else {
            ValueBox(field, current, onSave)
        }
    }
}

/**
 * 文本/数字/密码输入。
 *
 * 受控输入需要本地草稿：初值只认打开本页时的快照，避免写回存储后又被外部刷新打断输入。
 */
@Composable
private fun ValueBox(field: ConfigField, initial: String, onSave: (String) -> Unit) {
    val p = LocalQq.current
    var text by remember(field.key, field.label) { mutableStateOf(initial) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(p.bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (text.isEmpty()) {
            Text(
                field.default.ifBlank { "未设置" },
                color = p.t3,
                fontSize = 13.sp,
            )
        }
        BasicTextField(
            value = text,
            onValueChange = { next ->
                text = next
                // 数字字段中途会出现空串，只把能解析的值写回去，避免插件读到脏数据。
                if (field.type != ConfigFieldType.Number || next.isBlank() || next.toDoubleOrNull() != null) {
                    onSave(next)
                }
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = p.t1),
            visualTransformation = if (field.type == ConfigFieldType.Secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = if (field.type == ConfigFieldType.Number) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 下拉选择：空值时显示插件声明的第一个选项，点开由宿主画菜单，不依赖插件代码。 */
@Composable
private fun SelectBox(field: ConfigField, current: String, onSave: (String) -> Unit) {
    val p = LocalQq.current
    var expanded by remember { mutableStateOf(false) }
    var picked by remember(field.key, field.label) { mutableStateOf(current) }
    val shown = picked.ifBlank { field.options.firstOrNull().orEmpty() }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(p.bg)
                .clickable(enabled = field.options.isNotEmpty()) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shown.ifBlank { "未设置" },
                color = p.t1,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (field.options.isNotEmpty()) Text("▾", color = p.t3, fontSize = 13.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = p.t1) },
                    onClick = {
                        expanded = false
                        picked = option
                        onSave(option)
                    },
                )
            }
        }
    }
}
