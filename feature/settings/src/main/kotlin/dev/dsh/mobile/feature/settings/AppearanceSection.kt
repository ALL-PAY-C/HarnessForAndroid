package dev.dsh.mobile.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.data.settings.ThemeMode
import dev.dsh.mobile.core.model.BackMaterial
import dev.dsh.mobile.design.AppIconBadge
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.startup.StartupAnimations
import dev.dsh.mobile.design.startup.StartupSpec
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 设置页「外观」区块的全部内容（Wallpaper 式可配置）。
 *
 * 唯一对外入口就是 [AppearanceSectionBody]；宿主页面把它放进自己的折叠卡片里即可，
 * 内部的每一行都是本文件私有实现，不复用 SettingsScreen 的私有组件（那些是 file-private）。
 *
 * 读写的键全部来自 [SettingsUiState.settings]（AppSettings）：appIconStyle / customIconUri /
 * backgroundUri / backgroundDim / accentHue / borderWidthDp / backMaterial / glassAlpha /
 * glassBlurDp，以及原有的 themeMode / cornerRadiusDp / fontScale / density / compactBubbles。
 */
@Composable
fun AppearanceSectionBody(state: SettingsUiState, vm: SettingsViewModel) {
    val context = LocalContext.current
    val s = state.settings

    // OpenDocument 给的是一次性读权限，必须立刻 take 下来，否则重启后 uri 就废了。
    fun keep(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // 选完图先在 IO 线程把内容复制进 filesDir，再存绝对路径：
    // 一来 SAF 授权被回收也不会白屏，二来路径每次都新 → Theme 的缓存键变化 → 换图立刻可见。
    // 复制失败（读不到流）才退回存原始 uri，并照旧 take 一次权限。
    val scope = rememberCoroutineScope()
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val saved = AppearanceImages.import(context, uri, "icon")
            if (saved == null) {
                keep(uri)
                vm.setCustomIconUri(uri.toString())
            } else {
                vm.setCustomIconUri(saved)
            }
            vm.setAppIconStyle("custom")
        }
    }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val saved = AppearanceImages.import(context, uri, "background")
            if (saved == null) {
                keep(uri)
                vm.setBackgroundUri(uri.toString())
            } else {
                vm.setBackgroundUri(saved)
            }
        }
    }

    val pickAnimation = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val saved = AppearanceImages.import(context, uri, "startup")
            if (saved == null) {
                keep(uri)
                vm.setStartupAnimation("image:" + uri)
            } else {
                vm.setStartupAnimation("image:" + saved)
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {

        // ---------- 图标 ----------
        ApGroup("图标")
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(56.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                if (s.appIconStyle == "custom" && s.customIconUri.isNotBlank()) "当前：自定义图片" else "当前：内置图标",
                color = LocalQq.current.t2,
                fontSize = 13.sp,
            )
        }
        ApSegment(
            label = "图标样式",
            options = listOf("system" to "默认", "custom" to "图片"),
            selected = s.appIconStyle,
            onSelect = { vm.setAppIconStyle(it) },
        )
        ApAction(
            text = if (s.customIconUri.isBlank()) "选择图标图片…" else "更换图标图片…",
            onClick = { pickIcon.launch(arrayOf("image/*")) },
        )
        ApAction(
            text = "清除自定义图标",
            enabled = s.customIconUri.isNotBlank() || s.appIconStyle == "custom",
            onClick = {
                vm.setCustomIconUri("")
                vm.setAppIconStyle("system")
            },
        )
        ApHint("图片从系统文件选择器取（image/*），会复制一份到应用私有目录（不再依赖 SAF 授权），非正方形图按等比缩放不裁切。")

        QqCardDivider()

        // ---------- 主题 ----------
        ApGroup("主题")
        ApSegment(
            label = "深浅",
            options = ThemeMode.entries.map {
                it.name to when (it) {
                    ThemeMode.System -> "跟随系统"
                    ThemeMode.Light -> "浅色"
                    ThemeMode.Dark -> "深色"
                }
            },
            selected = s.themeMode.name,
            onSelect = { name -> ThemeMode.entries.firstOrNull { it.name == name }?.let { vm.setThemeMode(it) } },
        )
        ApAction(
            text = if (s.backgroundUri.isBlank()) "自定义背景图片…" else "更换背景图片…",
            onClick = { pickBackground.launch(arrayOf("image/*")) },
        )
        ApAction(
            text = "清除背景图片",
            enabled = s.backgroundUri.isNotBlank(),
            onClick = { vm.setBackgroundUri("") },
        )
        ApSlider("背景压暗", String.format(Locale.US, "%.2f", s.backgroundDim)) {
            Slider(
                value = s.backgroundDim,
                onValueChange = { vm.setBackgroundDim(it) },
                valueRange = 0f..0.8f,
            )
        }
        ApHint(
            if (s.backgroundUri.isBlank()) "未设置背景图时按主题走纯色。"
            else "背景图铺满全屏（centerCrop），压暗用来救亮色壁纸上的正文可读性；材质选「无」时壁纸不绘制。",
        )

        QqCardDivider()

        // ---------- UI ----------
        ApGroup("UI")
        ApSlider("边框宽度", "${s.borderWidthDp}dp") {
            Slider(
                value = s.borderWidthDp.toFloat(),
                onValueChange = { vm.setBorderWidth(it.toInt()) },
                valueRange = 0f..8f,
                steps = 7,
            )
        }
        ApSlider(
            label = "色调",
            value = if (s.accentHue < 0f) "跟随主色" else "${s.accentHue.toInt()}°",
        ) {
            Slider(
                value = if (s.accentHue < 0f) 0f else s.accentHue,
                onValueChange = { vm.setAccentHue(it) },
                valueRange = 0f..360f,
            )
        }
        ApAction(text = "色调改回跟随主色", enabled = s.accentHue >= 0f) { vm.setAccentHue(-1f) }

        // ---------- 启动动画 ----------
        // 默认关：spec 空串时不叠任何东西。写法见 dev.dsh.mobile.design.startup.StartupAnimations。
        ApGroup("启动动画")
        val spec = StartupAnimations.parse(s.startupAnimation)
        ApSegment(
            label = "样式",
            options = listOf(
                "off" to "关闭",
                "builtin:fade" to "淡入",
                "builtin:draw-h" to "画 H",
                "builtin:bounce" to "弹跳",
            ),
            selected = when (spec) {
                StartupSpec.Off -> "off"
                is StartupSpec.Builtin -> "builtin:" + spec.id
                else -> ""
            },
            onSelect = { vm.setStartupAnimation(if (it == "off") "" else it) },
        )
        ApChips(
            options = listOf(
                "0" to "默认时长",
                "400" to "快",
                "900" to "标准",
                "1600" to "慢",
            ),
            selected = s.startupAnimationMs.toString(),
            onSelect = { vm.setStartupAnimationMs(it.toIntOrNull() ?: 0) },
        )
        ApAction(
            text = if (spec is StartupSpec.Image) "更换动画图片…" else "选择动画图片 / GIF…",
            onClick = { pickAnimation.launch(arrayOf("image/*")) },
        )
        var customSpec by remember(s.startupAnimation) { mutableStateOf(if (spec is StartupSpec.Plugin) s.startupAnimation else "") }
        OutlinedTextField(
            value = customSpec,
            onValueChange = { customSpec = it },
            singleLine = true,
            label = { Text("自定义 spec") },
            placeholder = { Text("plugin:my-anim") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 6.dp),
        )
        ApAction(text = "应用自定义 spec", enabled = customSpec.isNotBlank()) { vm.setStartupAnimation(customSpec.trim()) }
        ApAction(text = "关闭启动动画", enabled = s.startupAnimation.isNotBlank()) { vm.setStartupAnimation("") }
        ApHint("默认关闭。spec 写法：builtin:fade / builtin:draw-h / builtin:bounce / image:<uri> / plugin:<id>；插件注册同名样式即可出现在这里，宿主认不出就退回不播。点屏幕可跳过。")

        QqCardDivider()
        // 材质选择：取代 v0.05 的「磨砂玻璃」开关。开关只有开/关，液态和亚克力没地方放，
        // 而用户反馈「玻璃质感切换的东西不对」——本质上就是这一个 bool 表达不了三种观感。
        ApGroup("面板材质")
        ApChips(
            options = BackMaterial.entries.map { it.key to it.label() },
            selected = s.material.key,
            onSelect = { vm.setBackMaterial(it) },
        )
        ApHint(s.material.hint())
        if (s.material.strengthApplies) {
            ApSlider("强度", String.format(Locale.US, "%.2f", s.glassAlpha)) {
                Slider(
                    value = s.glassAlpha,
                    onValueChange = { vm.setGlassAlpha(it) },
                    // 上下限跟读取侧共用 BackMaterial 的常量，不再出现「拖到 1 之后页面实底盖住壁纸」。
                    valueRange = BackMaterial.MIN_ALPHA..BackMaterial.MAX_ALPHA,
                )
            }
        }
        ApSlider(
            label = "雾化半径",
            value = when {
                s.material == BackMaterial.NONE -> "无材质时不雾化"
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "本机不支持"
                else -> "${s.glassBlurDp}dp"
            },
        ) {
            Slider(
                value = s.glassBlurDp.toFloat(),
                onValueChange = { vm.setGlassBlur(it.toInt()) },
                valueRange = 0f..40f,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && s.material != BackMaterial.NONE,
            )
        }
        ApHint(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "雾化（RenderEffect）只在 Android 12+ 生效；液态 / 亚克力自带模糊下限，滑块只能往上加。"
            } else {
                "当前系统低于 Android 12：不做雾化，只保留半透明降级。"
            },
        )

        QqCardDivider()

        // ---------- 细节 ----------
        ApGroup("细节")
        ApSlider("圆角", "${s.cornerRadiusDp}dp") {
            Slider(
                value = s.cornerRadiusDp.toFloat(),
                onValueChange = { vm.setRadius(it.toInt()) },
                valueRange = 4f..28f,
            )
        }
        ApSlider("字号", String.format(Locale.US, "%.2f", s.fontScale)) {
            Slider(
                value = s.fontScale,
                onValueChange = { vm.setFontScale(it) },
                valueRange = 0.85f..1.25f,
            )
        }
        ApSlider("列表密度", String.format(Locale.US, "%.2f", s.density)) {
            Slider(
                value = s.density,
                onValueChange = { vm.setDensity(it) },
                valueRange = 0.85f..1.2f,
            )
        }
        ApSwitch("紧凑气泡", "减小气泡内边距", s.compactBubbles) { vm.setCompact(it) }
        ApHint("圆角 / 字号 / 密度 / 紧凑气泡与「恢复默认外观」共用同一组键。")
    }
}

/** 分组小标题：比 SettingsScreen 的一级标题更轻，避免和折叠卡片抢层级。 */
@Composable
private fun ApGroup(title: String) {
    val p = LocalQq.current
    Text(
        title,
        color = p.t3,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun ApHint(text: String) {
    val p = LocalQq.current
    Text(
        text,
        color = p.t3,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 4.dp),
    )
}

/** 互斥分段控件（和设置页其余处同一套视觉）。 */
@Composable
private fun ApSegment(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
        options.forEach { (value, text) ->
            val on = value == selected
            Text(
                text,
                color = if (on) Color.White else p.t2,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(start = 5.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) p.accent else p.hover)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

/** 材质中文名：只有 UI 需要，所以留在 feature:settings，core:model 里不放界面文案。 */
private fun BackMaterial.label(): String = when (this) {
    BackMaterial.NONE -> "无"
    BackMaterial.MATTE -> "磨砂"
    BackMaterial.LIQUID -> "液态"
    BackMaterial.ACRYLIC -> "亚克力"
}

private fun BackMaterial.hint(): String = when (this) {
    BackMaterial.NONE -> "页面完全实底，壁纸不参与绘制。"
    BackMaterial.MATTE -> "半透明面板 + 背景雾化；强度调面板不透明度。"
    BackMaterial.LIQUID -> "更透的面板 + 顶部高光与边缘折射，壁纸带一层轻微模糊。"
    BackMaterial.ACRYLIC -> "高不透明度 + 细噪点，重磨砂；强度固定，雾化滑块仍可用。"
}

/** 「强度」滑块只对磨砂 / 液态有意义：亚克力是常量不透明度，无材质则全实底。 */
private val BackMaterial.strengthApplies: Boolean
    get() = this == BackMaterial.MATTE || this == BackMaterial.LIQUID

/**
 * 材质单选 chip 行。铺满一行四个，比原来的开关多两档选择；
 * 选中的那个用主色填充，和 [ApSegment] 保持同一套视觉。
 */
@Composable
private fun ApChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, text) ->
            val on = value == selected
            Text(
                text,
                color = if (on) Color.White else p.t2,
                fontSize = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) p.accent else p.hover)
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ApSlider(label: String, value: String, content: @Composable () -> Unit) {
    val p = LocalQq.current
    Column(Modifier.padding(horizontal = 13.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = p.t1, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(value, color = p.t3, fontSize = 12.5.sp)
        }
        content()
    }
}

@Composable
private fun ApSwitch(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = p.t1, fontSize = 15.sp)
            Text(subtitle, color = p.t3, fontSize = 11.5.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 文字按钮行：选图 / 清除这类一次性动作。 */
@Composable
private fun ApAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(if (enabled) p.accent else p.t3))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (enabled) p.accent else p.t3, fontSize = 14.sp)
    }
}
