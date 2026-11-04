package dev.dsh.mobile.feature.plugins

import androidx.compose.foundation.background
import dev.dsh.mobile.design.R as DesignR
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.design.text
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
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
    val context = LocalContext.current
    // 重置时先收键盘：输入框还focus着的话，IME 会把旧内容顶回来，覆盖刚写回的默认值。
    val focusManager = LocalFocusManager.current
    // 重置写回存储后这一页的快照不会重建；本地覆盖让输入框立刻显示默认值。
    var overrides by remember(entry.pluginId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 重置计数器：本地覆盖值可能和快照里的旧值相同，只有换 key 才能把输入框里的草稿换掉。
    var resetTick by remember(entry.pluginId) { mutableStateOf(0) }
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
                    Text(failure, color = p.accent2, fontSize = MaterialTheme.typography.bodyMedium.fontSize, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(DesignR.string.plugins_set_ui_broken),
                        color = p.t3,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    )
                }
            }
        }

        // 设置页统一走 schema：分组标题 / 开关 / 输入 / 下拉 / 滑杆 / 按钮 / 说明行都在这里画。
        // 每一行单独兜底：某一行（或者未知类型降级）画不出来只损失那一行，整页照常可用。
        if (!entry.schema.isEmpty) {
            GroupFrame {
                GroupHeader(
                    label = stringResource(DesignR.string.plugins_set_group),
                    count = entry.schema.items.count { it !is SettingItem.Section },
                    collapsible = false,
                )
                Column(Modifier.padding(vertical = 4.dp)) {
                    entry.schema.items.forEach { item ->
                        val rendered = runCatching {
                            SchemaRow(
                                item = item,
                                current = overrides[item.key] ?: currentValueOf(item, entry),
                                resetKey = resetTick,
                                onSave = { key, value -> onSave(key, value) },
                                onReset = { action ->
                                    val fallback = action.default
                                    if (fallback == null) {
                                        Toast.makeText(
                                            context,
                                            context.getString(DesignR.string.plugins_set_reset_missing, action.target),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        focusManager.clearFocus()
                                        overrides = overrides + (action.target to fallback)
                                        resetTick++
                                        onSave(action.target, fallback)
                                        Toast.makeText(
                                            context,
                                            context.getString(DesignR.string.plugins_set_reset_done),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                        if (rendered.isFailure) {
                            InlineNote(
                                stringResource(DesignR.string.plugins_set_row_failed, item.key),
                                stringResource(DesignR.string.plugins_set_row_failed_hint),
                            )
                        }
                    }
                }
            }
        }

        ExtraConfigSection(entry, onSave)

        if (page != null && entry.hasPage) {
            Text(
                stringResource(DesignR.string.plugins_set_ui_section),
                color = p.t3,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
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
                        stringResource(DesignR.string.plugins_set_open_browser),
                        color = p.t1,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        modifier = Modifier.weight(1f),
                    )
                    Text("↗", color = p.t3, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
                }
            }
        }

        entry.emptyText?.let { empty ->
            GroupFrame {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 14.dp)) {
                    Text(empty.text(), color = p.t2, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
                    Spacer(Modifier.height(4.dp))
                    Text(NO_SETTINGS_HINT.text(), color = p.t3, fontSize = MaterialTheme.typography.bodySmall.fontSize)
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
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("v" + entry.version, color = p.t3, fontSize = MaterialTheme.typography.bodySmall.fontSize)
            }
            if (entry.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(entry.description, color = p.t3, fontSize = MaterialTheme.typography.labelMedium.fontSize)
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.pluginId, color = p.t3, fontSize = MaterialTheme.typography.labelSmall.fontSize)
        }
    }
}

/** 重置按钮行：一行字 + 落点，点一下把 target 写回默认。默认值拿不到时置灰不可点。 */
@Composable
private fun ActionRow(action: ConfigAction, onReset: () -> Unit) {
    val p = LocalQq.current
    val enabled = action.default != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onReset() }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                action.label,
                color = if (enabled) p.t1 else p.t3,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            )
            Text(
                stringResource(
                    if (enabled) DesignR.string.plugins_set_reset_hint_on else DesignR.string.plugins_set_reset_hint_off,
                    action.target,
                ),
                color = p.t3,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
            )
        }
        Text("↺", color = if (enabled) p.accent else p.t3, fontSize = MaterialTheme.typography.bodyLarge.fontSize)
    }
}

/** 一行设置项：开关单独一行，其余字段用「标签 + 输入框」两段式。 */
@Composable
private fun ConfigFieldRow(field: ConfigField, current: String, resetKey: Int, onSave: (String) -> Unit) {
    val p = LocalQq.current
    if (field.type == ConfigFieldType.Switch) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(field.label, color = p.t1, fontSize = MaterialTheme.typography.bodyLarge.fontSize)
                if (field.hint.isNotBlank()) {
                    Text(field.hint, color = p.t3, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                }
            }
            // 同 ValueBox：写回存储后列表快照不会重组，开关状态要留在本地才即时可见。
            var on by remember(field.key, current, resetKey) { mutableStateOf(switchOn(current)) }
            Switch(checked = on, onCheckedChange = { on = it; onSave(it.toString()) })
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp)) {
        Text(field.label, color = p.t1, fontSize = MaterialTheme.typography.bodyLarge.fontSize)
        if (field.hint.isNotBlank()) {
            Text(field.hint, color = p.t3, fontSize = MaterialTheme.typography.bodySmall.fontSize)
        }
        Spacer(Modifier.height(6.dp))
        if (field.type == ConfigFieldType.Select) {
            SelectBox(field, current, resetKey, onSave)
        } else {
            ValueBox(field, current, resetKey, onSave)
        }
    }
}

/**
 * 文本/数字/密码输入。
 *
 * 受控输入需要本地草稿：初值只认打开本页时的快照，避免写回存储后又被外部刷新打断输入。
 */
@Composable
private fun ValueBox(field: ConfigField, initial: String, resetKey: Int, onSave: (String) -> Unit) {
    val p = LocalQq.current
    // initial 也参与 key：重置把默认值写回来时，输入框跟着换掉旧内容。
    var text by remember(field.key, field.label, initial, resetKey) { mutableStateOf(initial) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(p.bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (text.isEmpty()) {
            Text(
                field.default.ifBlank { stringResource(DesignR.string.plugins_set_not_set) },
                color = p.t3,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
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
            // type=text（例如破甲注入词）按多行编辑：单行输入框既看不到全文也换不了行。
            singleLine = field.type != ConfigFieldType.Text,
            minLines = if (field.type == ConfigFieldType.Text) 2 else 1,
            maxLines = if (field.type == ConfigFieldType.Text) 8 else 1,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.t1),
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
private fun SelectBox(field: ConfigField, current: String, resetKey: Int, onSave: (String) -> Unit) {
    val p = LocalQq.current
    var expanded by remember { mutableStateOf(false) }
    var picked by remember(field.key, field.label, resetKey) { mutableStateOf(current) }
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
                shown.ifBlank { stringResource(DesignR.string.plugins_set_not_set) },
                color = p.t1,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                modifier = Modifier.weight(1f),
            )
            if (field.options.isNotEmpty()) Text("▾", color = p.t3, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
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

/**
 * 自定义配置：插件没声明 config 时，用户自己加键值。
 *
 * 写回走同一个 [PluginSettingsEntry.values] 存储，插件的 ctx.config.get(key, fallback)
 * 立刻能读到；已存在的键用 ConfigFieldRow 直接改，新增走两段式输入 + 保存。
 */
@Composable
private fun ExtraConfigSection(entry: PluginSettingsEntry, onSave: (key: String, value: String) -> Unit) {
    val p = LocalQq.current
    var adding by remember(entry.pluginId) { mutableStateOf(false) }
    var draftKey by remember(entry.pluginId) { mutableStateOf("") }
    var draftValue by remember(entry.pluginId) { mutableStateOf("") }
    // 详情页拿到的是打开时的快照，存储写回不会重建这一页；本地存一份，改完立刻看得见。
    var saved by remember(entry.pluginId) { mutableStateOf(entry.extraValues) }
    GroupFrame {
        GroupHeader(label = stringResource(DesignR.string.plugins_set_custom_group), count = saved.size, collapsible = false)
        Column(Modifier.padding(vertical = 4.dp)) {
            saved.forEach { (key, value) ->
                ConfigFieldRow(ConfigField(key = key, label = key), value, 0) { text ->
                    saved = saved + (key to text)
                    onSave(key, text)
                }
            }
            if (adding) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp)) {
                    SimpleInput(stringResource(DesignR.string.plugins_set_key_hint), draftKey) { draftKey = it }
                    Spacer(Modifier.height(6.dp))
                    SimpleInput(stringResource(DesignR.string.plugins_set_value_hint), draftValue) { draftValue = it }
                    Spacer(Modifier.height(9.dp))
                    // 两个按钮各占一半宽度：贴在文本上的 clickable 命中区只有字形大小，
                    // 手指点在字缝里就什么都不会发生。
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(p.bg)
                                .clickable {
                                    adding = false
                                    draftKey = ""
                                    draftValue = ""
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(DesignR.string.common_cancel),
                                color = p.t3,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            )
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(p.bg)
                                .clickable(enabled = draftKey.isNotBlank()) {
                                    saved = saved + (draftKey.trim() to draftValue)
                                    onSave(draftKey.trim(), draftValue)
                                    adding = false
                                    draftKey = ""
                                    draftValue = ""
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(DesignR.string.common_save),
                                color = if (draftKey.isBlank()) p.t3 else p.accent,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            } else {
                GroupChild(onClick = { adding = true }) {
                    Text(
                        stringResource(DesignR.string.plugins_set_add),
                        color = p.t1,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                        modifier = Modifier.weight(1f),
                    )
                    Text("+", color = p.t3, fontSize = MaterialTheme.typography.bodyLarge.fontSize)
                }
            }
        }
    }
}

/**
 * schema 的一行 → 宿主自己的控件。
 *
 * 只做映射，不做容错以外的判断：类型认不出时 [settingsSchema] 早就换成 Info 行，
 * 走到这里还抛异常说明数据坏了 —— 外层 runCatching 会把它压成一条可见提示。
 */
@Composable
private fun SchemaRow(
    item: SettingItem,
    current: String,
    resetKey: Int,
    onSave: (key: String, value: String) -> Unit,
    onReset: (ConfigAction) -> Unit,
) {
    when (item) {
        is SettingItem.Section -> SectionRow(item.title)
        is SettingItem.Toggle -> ConfigFieldRow(
            ConfigField(item.key, item.label, ConfigFieldType.Switch, current, item.hint),
            current,
            resetKey,
        ) { onSave(item.key, it) }
        is SettingItem.Entry -> ConfigFieldRow(
            ConfigField(
                key = item.key,
                label = item.label,
                type = when {
                    item.secret -> ConfigFieldType.Secret
                    item.numeric -> ConfigFieldType.Number
                    else -> ConfigFieldType.Text
                },
                default = current,
                hint = item.hint,
            ),
            current,
            resetKey,
        ) { onSave(item.key, it) }
        is SettingItem.Choice -> ConfigFieldRow(
            ConfigField(item.key, item.label, ConfigFieldType.Select, current, item.hint, item.options),
            current,
            resetKey,
        ) { onSave(item.key, it) }
        is SettingItem.Slider -> SliderRow(item, current, resetKey) { onSave(item.key, it) }
        is SettingItem.Action -> ActionRow(ConfigAction(item.key, item.label, item.target, item.default)) {
            onReset(ConfigAction(item.key, item.label, item.target, item.default))
        }
        is SettingItem.Info -> InfoRow(item.label, item.hint)
    }
}

/** 读当前值：用户改过的优先（overrides），否则插件的默认值。 */
private fun currentValueOf(item: SettingItem, entry: PluginSettingsEntry): String = when (item) {
    is SettingItem.Toggle -> if (switchOn(entry.valueOf(ConfigField(item.key, item.label, ConfigFieldType.Switch, item.value, item.hint))))
        "true" else "false"
    else -> entry.values[item.key] ?: when (item) {
        is SettingItem.Entry -> item.value
        is SettingItem.Choice -> item.value
        is SettingItem.Slider -> sliderText(item)
        else -> ""
    }
}

/** 分组标题：一段设置的小标题，不是卡片，跟着 GroupFrame 走。 */
@Composable
private fun SectionRow(title: String) {
    val p = LocalQq.current
    Text(
        title,
        color = p.t3,
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** 滑杆：本地状态跟着手指走，松手才写回存储（拖动期间写会一直打存储）。 */
@Composable
private fun SliderRow(item: SettingItem.Slider, current: String, resetKey: Int, onSave: (String) -> Unit) {
    val p = LocalQq.current
    var value by remember(item.key, resetKey) {
        mutableFloatStateOf(current.toFloatOrNull()?.coerceIn(item.min, item.max) ?: item.value)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, color = p.t1, fontSize = MaterialTheme.typography.bodyLarge.fontSize, modifier = Modifier.weight(1f))
            Text(sliderText(item, value), color = p.t3, fontSize = MaterialTheme.typography.bodySmall.fontSize)
        }
        if (item.hint.isNotBlank()) {
            Text(item.hint, color = p.t3, fontSize = MaterialTheme.typography.bodySmall.fontSize)
        }
        Slider(
            value = value,
            onValueChange = { next -> value = next.coerceIn(item.min, item.max) },
            onValueChangeFinished = { onSave(sliderText(item, value)) },
            valueRange = item.min..item.max,
            steps = item.steps,
        )
    }
}

/** 只读行：插件声明的说明文字，或未知类型的降级落点。 */
@Composable
private fun InfoRow(label: String, hint: String) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp)) {
        Text(label, color = p.t1, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
        if (hint.isNotBlank()) {
            Text(hint, color = p.t3, fontSize = MaterialTheme.typography.bodySmall.fontSize)
        }
    }
}

/** 单行渲染失败的可见兜底：这一行画不出来，其余设置照旧，且用户知道是哪一条。 */
@Composable
private fun InlineNote(title: String, detail: String) {
    val p = LocalQq.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp)) {
        Text(title, color = p.accent, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
        Text(detail, color = p.t3, fontSize = MaterialTheme.typography.bodySmall.fontSize)
    }
}

/** 自定义配置用的单行输入，样式与 ValueBox 对齐。 */
@Composable
private fun SimpleInput(placeholder: String, value: String, onChange: (String) -> Unit) {
    val p = LocalQq.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(p.bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = p.t3, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.t1),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}