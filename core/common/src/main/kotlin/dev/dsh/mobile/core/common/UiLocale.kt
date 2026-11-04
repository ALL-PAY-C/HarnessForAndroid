package dev.dsh.mobile.core.common

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** 界面语言三态。System = 跟随系统首选语言（zh* → 中文，其余 → 英文）。 */
enum class UiLanguage { System, Zh, En }

/**
 * 进程级语言锁存（T9.11 方案 §2.2 第一段）。
 *
 * 为什么要有这个对象：Application.attachBaseContext 必须在 onCreate 之前同步拿到语言，
 * 而 SettingsStore 的 DataStore 是异步读，等它就等于冷启动先画中文再跳英文（闪屏）。
 * 所以 SettingsStore.setUiLanguage 同时往 SharedPreferences("ui_locale") 写一份镜像，
 * 启动时 latch 只读那一个 key。DataStore 仍是唯一事实来源，prefs 只是启动锁存副本。
 *
 * 纯逻辑（resolveTag / languageOf / localeOf / latchTag）不碰 Android API，可跑 JVM 单测；
 * 只有 latch / wrap / appContext / refresh / mirror 需要 Context。
 */
object UiLocale {

    const val PREFS = "ui_locale"
    const val KEY_LANG = "lang"

    /** null = 跟随系统。只接受 "zh" / "en"，其余一律归一成 null。 */
    @Volatile
    private var tag: String? = null

    /** System 判定用的系统语言。第一次取到就固定，避免 wrap 里 setDefault 之后自读自改。 */
    @Volatile
    private var systemLocale: Locale? = null

    /** 非 Compose 表面（通知 / Toast / 前台服务 / a11y）用的已包装 Context，refresh 重建。 */
    @Volatile
    private var app: Context? = null

    fun tagOf(language: UiLanguage): String? = when (language) {
        UiLanguage.Zh -> "zh"
        UiLanguage.En -> "en"
        UiLanguage.System -> null
    }

    fun resolveTag(saved: String?): String? = saved?.takeIf { it == "zh" || it == "en" }

    fun languageOf(tag: String?): UiLanguage = when (resolveTag(tag)) {
        "zh" -> UiLanguage.Zh
        "en" -> UiLanguage.En
        else -> UiLanguage.System
    }

    /** 三态归一：Zh/En 不随系统变动；System 看系统首选语言 zh* → 中文，其余 → 英文。 */
    fun localeOf(language: UiLanguage, system: Locale): Locale = when (language) {
        UiLanguage.Zh -> Locale.SIMPLIFIED_CHINESE
        UiLanguage.En -> Locale.ENGLISH
        UiLanguage.System ->
            if (system.language.equals("zh", ignoreCase = true)) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
    }

    fun latchTag(saved: String?) {
        if (systemLocale == null) systemLocale = Locale.getDefault()
        tag = resolveTag(saved)
    }

    fun latch(ctx: Context) = latchTag(
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null),
    )

    fun language(): UiLanguage = languageOf(tag)

    private fun systemOrDefault(): Locale = systemLocale ?: Locale.getDefault().also { systemLocale = it }

    fun locale(): Locale = localeOf(language(), systemOrDefault())

    /**
     * 应用级包装：返回 createConfigurationContext 的 ConfigurationContext（ContextImpl）。
     *
     * 只能给 Application / Service / 通知用。框架在 ActivityThread 里会把 receiver 的 base 强转
     * ContextImpl，套自定义 wrapper 会 ClassCastException。Compose 侧请用 [wrapForUi]。
     */
    fun wrap(base: Context, language: UiLanguage): Context {
        Locale.setDefault(localeOf(language, systemOrDefault()))
        return base.createConfigurationContext(configOf(base, language))
    }

    /**
     * Compose 侧包装：返回 [ContextWrapper]，链路仍通到 Activity。
     *
     * activity-compose 的 `findActivity` / `findOwner`（ActivityResultRegistryOwner、LifecycleOwner、
     * OnBackPressedDispatcherOwner）都沿 `ContextWrapper.baseContext` 往上爬找 Activity。裸
     * ContextImpl 不是 wrapper，链路一断就炸——设置子页一进去就
     * 「No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner」。
     */
    fun wrapForUi(base: Context, language: UiLanguage): Context {
        Locale.setDefault(localeOf(language, systemOrDefault()))
        return LocalizedContext(base, base.createConfigurationContext(configOf(base, language)))
    }

    private fun configOf(base: Context, language: UiLanguage): Configuration {
        val loc = localeOf(language, systemOrDefault())
        return Configuration(base.resources.configuration).apply {
            setLocale(loc)
            setLayoutDirection(loc)
        }
    }

    fun wrap(base: Context): Context = wrap(base, language())

    fun appContext(base: Context): Context = app ?: wrap(base.applicationContext).also { app = it }

    /** 切换语言后调用：否则下一条通知还是旧语言（非 Compose 表面不参与重组）。 */
    fun refresh(base: Context) {
        app = wrap(base.applicationContext, language())
    }

    /**
     * 把设置值补写进启动镜像。DataStore 是唯一事实来源，这里只在镜像与设置不一致时纠正
     * （用户清数据、或从旧版本升级上来 prefs 还是空的时候）。
     */
    fun mirror(base: Context, language: UiLanguage) {
        val prefs = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val want = tagOf(language)
        if (prefs.getString(KEY_LANG, null) == want) return
        prefs.edit().apply {
            if (want == null) remove(KEY_LANG) else putString(KEY_LANG, want)
        }.apply()
        latchTag(want)
        refresh(base)
    }
}

/**
 * 只换资源不换链路：base 仍是原 Context，Activity / ActivityResultRegistry / Lifecycle 的查找照旧。
 * getTheme 走 base，避免丢掉 Activity 主题属性。
 */
private class LocalizedContext(
    base: Context,
    private val configured: Context,
) : ContextWrapper(base) {
    override fun getResources(): Resources = configured.resources

    override fun getAssets(): AssetManager = configured.assets

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
        configured.createConfigurationContext(overrideConfiguration)
}