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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.common.UiLanguage
import dev.dsh.mobile.core.data.settings.ThemeMode
import dev.dsh.mobile.core.model.BackMaterial
import dev.dsh.mobile.design.AppIconBadge
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.SectionHeading
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.design.startup.StartupAnimations
import dev.dsh.mobile.design.R as DesignR
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
        ApGroup(stringResource(DesignR.string.appearance_group_icon))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SectionSpacing.horizontal, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(56.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                if (s.appIconStyle == "custom" && s.customIconUri.isNotBlank()) stringResource(DesignR.string.appearance_icon_current_custom) else stringResource(DesignR.string.appearance_icon_current_builtin),
                color = LocalQq.current.t2,
                fontSize = 13.sp,
            )
        }
        ApSegment(
            label = stringResource(DesignR.string.appearance_icon_style),
            options = listOf("system" to stringResource(DesignR.string.appearance_icon_default), "custom" to stringResource(DesignR.string.appearance_icon_custom)),
            selected = s.appIconStyle,
            onSelect = { vm.setAppIconStyle(it) },
        )
        ApAction(
            text = if (s.customIconUri.isBlank()) stringResource(DesignR.string.appearance_icon_pick) else stringResource(DesignR.string.appearance_icon_replace),
            onClick = { pickIcon.launch(arrayOf("image/*")) },
        )
        ApAction(
            text = stringResource(DesignR.string.appearance_icon_clear),
            enabled = s.customIconUri.isNotBlank() || s.appIconStyle == "custom",
            onClick = {
                vm.setCustomIconUri("")
                vm.setAppIconStyle("system")
            },
        )
        ApHint(stringResource(DesignR.string.appearance_icon_hint))

        QqCardDivider()

        // ---------- 主题 ----------
        ApGroup(stringResource(DesignR.string.appearance_group_theme))
        ApSegment(
            label = stringResource(DesignR.string.appearance_theme_mode),
            options = ThemeMode.entries.map {
                it.name to when (it) {
                    ThemeMode.System -> stringResource(DesignR.string.appearance_theme_system)
                    ThemeMode.Light -> stringResource(DesignR.string.appearance_theme_light)
                    ThemeMode.Dark -> stringResource(DesignR.string.appearance_theme_dark)
                }
            },
            selected = s.themeMode.name,
            onSelect = { name -> ThemeMode.entries.firstOrNull { it.name == name }?.let { vm.setThemeMode(it) } },
        )
        // ---------- 界面语言（T9.11 §2.1）----------
        // 值走 UiLanguage.name，与 themeMode 同一套写法；切换不重建 Activity，只重组。
        ApSegment(
            label = stringResource(DesignR.string.app_language_title),
            options = UiLanguage.entries.map {
                it.name to when (it) {
                    UiLanguage.System -> stringResource(DesignR.string.app_language_system)
                    UiLanguage.Zh -> stringResource(DesignR.string.app_language_zh)
                    UiLanguage.En -> stringResource(DesignR.string.app_language_en)
                }
            },
            selected = s.uiLanguage.name,
            onSelect = { name -> UiLanguage.entries.firstOrNull { it.name == name }?.let { vm.setUiLanguage(it) } },
        )
        ApHint(stringResource(DesignR.string.app_language_hint))
        ApAction(
            text = if (s.backgroundUri.isBlank()) stringResource(DesignR.string.appearance_bg_pick) else stringResource(DesignR.string.appearance_bg_replace),
            onClick = { pickBackground.launch(arrayOf("image/*")) },
        )
        ApAction(
            text = stringResource(DesignR.string.appearance_bg_clear),
            enabled = s.backgroundUri.isNotBlank(),
            onClick = { vm.setBackgroundUri("") },
        )
        ApSlider(stringResource(DesignR.string.appearance_bg_dim), String.format(Locale.US, "%.2f", s.backgroundDim)) {
            Slider(
                value = s.backgroundDim,
                onValueChange = { vm.setBackgroundDim(it) },
                valueRange = 0f..0.8f,
            )
        }
        ApHint(
            if (s.backgroundUri.isBlank()) stringResource(DesignR.string.appearance_bg_hint_none)
            else stringResource(DesignR.string.appearance_bg_hint_set),
        )

        QqCardDivider()

        // ---------- UI ----------
        ApGroup("UI")
        ApSlider(stringResource(DesignR.string.appearance_border_width), "${s.borderWidthDp}dp") {
            Slider(
                value = s.borderWidthDp.toFloat(),
                onValueChange = { vm.setBorderWidth(it.toInt()) },
                valueRange = 0f..8f,
                steps = 7,
            )
        }
        ApSlider(
            label = stringResource(DesignR.string.appearance_accent_hue),
            value = if (s.accentHue < 0f) stringResource(DesignR.string.appearance_hue_follow) else "${s.accentHue.toInt()}°",
        ) {
            Slider(
                value = if (s.accentHue < 0f) 0f else s.accentHue,
                onValueChange = { vm.setAccentHue(it) },
                valueRange = 0f..360f,
            )
        }
        ApAction(text = stringResource(DesignR.string.appearance_hue_reset), enabled = s.accentHue >= 0f) { vm.setAccentHue(-1f) }

        // ---------- 启动动画 ----------
        // 默认关：spec 空串时不叠任何东西。写法见 dev.dsh.mobile.design.startup.StartupAnimations。
        ApGroup(stringResource(DesignR.string.appearance_group_splash))
        val spec = StartupAnimations.parse(s.startupAnimation)
        ApSegment(
            label = stringResource(DesignR.string.appearance_splash_style),
            options = listOf(
                "off" to stringResource(DesignR.string.appearance_splash_off),
                "builtin:fade" to stringResource(DesignR.string.appearance_splash_fade),
                "builtin:draw-h" to stringResource(DesignR.string.appearance_splash_draw_h),
                "builtin:bounce" to stringResource(DesignR.string.appearance_splash_bounce),
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
                "0" to stringResource(DesignR.string.appearance_splash_dur_default),
                "400" to stringResource(DesignR.string.appearance_splash_dur_fast),
                "900" to stringResource(DesignR.string.appearance_splash_dur_normal),
                "1600" to stringResource(DesignR.string.appearance_splash_dur_slow),
            ),
            selected = s.startupAnimationMs.toString(),
            onSelect = { vm.setStartupAnimationMs(it.toIntOrNull() ?: 0) },
        )
        ApAction(
            text = if (spec is StartupSpec.Image) stringResource(DesignR.string.appearance_splash_replace) else stringResource(DesignR.string.appearance_splash_pick),
            onClick = { pickAnimation.launch(arrayOf("image/*")) },
        )
        var customSpec by remember(s.startupAnimation) { mutableStateOf(if (spec is StartupSpec.Plugin) s.startupAnimation else "") }
        OutlinedTextField(
            value = customSpec,
            onValueChange = { customSpec = it },
            singleLine = true,
            label = { Text(stringResource(DesignR.string.appearance_splash_custom_spec)) },
            placeholder = { Text("plugin:my-anim") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 6.dp),
        )
        ApAction(text = stringResource(DesignR.string.appearance_splash_apply_spec), enabled = customSpec.isNotBlank()) { vm.setStartupAnimation(customSpec.trim()) }
        ApAction(text = stringResource(DesignR.string.appearance_splash_off_action), enabled = s.startupAnimation.isNotBlank()) { vm.setStartupAnimation("") }
        ApHint(stringResource(DesignR.string.appearance_splash_hint))

        QqCardDivider()
        // 材质选择：取代 v0.05 的「磨砂玻璃」开关。开关只有开/关，液态和亚克力没地方放，
        // 而用户反馈「玻璃质感切换的东西不对」——本质上就是这一个 bool 表达不了三种观感。
        ApGroup(stringResource(DesignR.string.appearance_group_material))
        ApChips(
            options = BackMaterial.entries.map { it.key to it.label() },
            selected = s.material.key,
            onSelect = { vm.setBackMaterial(it) },
        )
        ApHint(s.material.hint())
        if (s.material.strengthApplies) {
            ApSlider(stringResource(DesignR.string.appearance_strength), String.format(Locale.US, "%.2f", s.glassAlpha)) {
                Slider(
                    value = s.glassAlpha,
                    onValueChange = { vm.setGlassAlpha(it) },
                    // 上下限跟读取侧共用 BackMaterial 的常量，不再出现「拖到 1 之后页面实底盖住壁纸」。
                    valueRange = BackMaterial.MIN_ALPHA..BackMaterial.MAX_ALPHA,
                )
            }
        }
        ApSlider(
            label = stringResource(DesignR.string.appearance_blur_radius),
            value = when {
                s.material == BackMaterial.NONE -> stringResource(DesignR.string.appearance_blur_none)
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> stringResource(DesignR.string.appearance_blur_unsupported)
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
                stringResource(DesignR.string.appearance_blur_hint)
            } else {
                stringResource(DesignR.string.appearance_blur_legacy)
            },
        )

        QqCardDivider()

        // ---------- 细节 ----------
        ApGroup(stringResource(DesignR.string.appearance_group_detail))
        ApSlider(stringResource(DesignR.string.appearance_corner_radius), "${s.cornerRadiusDp}dp") {
            Slider(
                value = s.cornerRadiusDp.toFloat(),
                onValueChange = { vm.setRadius(it.toInt()) },
                valueRange = 4f..28f,
            )
        }
        ApSlider(stringResource(DesignR.string.appearance_font_scale), String.format(Locale.US, "%.2f", s.fontScale)) {
            Slider(
                value = s.fontScale,
                onValueChange = { vm.setFontScale(it) },
                valueRange = 0.85f..1.25f,
            )
        }
        ApSlider(stringResource(DesignR.string.appearance_density), String.format(Locale.US, "%.2f", s.density)) {
            Slider(
                value = s.density,
                onValueChange = { vm.setDensity(it) },
                valueRange = 0.85f..1.2f,
            )
        }
        ApSwitch(stringResource(DesignR.string.appearance_compact_bubbles), stringResource(DesignR.string.appearance_compact_bubbles_hint), s.compactBubbles) { vm.setCompact(it) }
        ApHint(stringResource(DesignR.string.appearance_detail_hint))
    }
}

/**
 * 分组小标题：与设置页卡片内的分节标题是**同一个组件**（[SectionHeading]：t1 / 15sp / Medium），
 * 左边距也走同一个 [SectionSpacing.horizontal]，不再自绘一层更淡的小字。
 */
@Composable
private fun ApGroup(title: String) {
    SectionHeading(
        title = title,
        modifier = Modifier.padding(
            start = SectionSpacing.horizontal,
            end = SectionSpacing.horizontal,
            top = 10.dp,
            bottom = 4.dp,
        ),
    )
}

@Composable
private fun ApHint(text: String) {
    val p = LocalQq.current
    Text(
        text,
        // 12sp/18sp 是卡片副文案的字号；颜色用 t2（浅色主题 t3 只有 3.21:1，11sp 下基本读不出来）。
        color = p.t2,
        fontSize = qqFontBody(12.sp),
        lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SectionSpacing.horizontal, vertical = 4.dp),
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
        Modifier.fillMaxWidth().padding(horizontal = SectionSpacing.horizontal, vertical = 11.dp),
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
@Composable
private fun BackMaterial.label(): String = when (this) {
    BackMaterial.NONE -> stringResource(DesignR.string.appearance_material_none)
    BackMaterial.MATTE -> stringResource(DesignR.string.appearance_material_matte)
    BackMaterial.LIQUID -> stringResource(DesignR.string.appearance_material_liquid)
    BackMaterial.ACRYLIC -> stringResource(DesignR.string.appearance_material_acrylic)
}

@Composable
private fun BackMaterial.hint(): String = when (this) {
    BackMaterial.NONE -> stringResource(DesignR.string.appearance_material_none_hint)
    BackMaterial.MATTE -> stringResource(DesignR.string.appearance_material_matte_hint)
    BackMaterial.LIQUID -> stringResource(DesignR.string.appearance_material_liquid_hint)
    BackMaterial.ACRYLIC -> stringResource(DesignR.string.appearance_material_acrylic_hint)
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
    Column(Modifier.padding(horizontal = SectionSpacing.horizontal, vertical = 6.dp)) {
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
        Modifier.fillMaxWidth().padding(horizontal = SectionSpacing.horizontal, vertical = 9.dp),
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
            .padding(horizontal = SectionSpacing.horizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(text, color = if (enabled) p.accent else p.t3, fontSize = 14.sp)
    }
}
