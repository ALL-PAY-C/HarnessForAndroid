package dev.dsh.mobile.pluginhost

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the host knows about *installed* plugin packages.
 *
 * Kept apart from [PluginStore] (which only remembers enabled flags) so that a
 * package can be listed, configured and removed without ever being loaded.
 * Values are stored as JSON in SharedPreferences: the shape is small, and one
 * atomic blob is easier to snapshot for the backup/rollback path than a schema.
 *
 * 配置值（`values.<id>`）落盘一律是密文，见 [PluginConfigVault]；旧版本写下的明文在
 * 第一次读到时就地迁移成密文（读到的值不变，且迁移幂等：写回同一个键，不新增键）。
 *
 * 解密是 PBKDF2-120k（[PluginConfigVault]），而配置读写发生在 UI 回调里（每次按键 / 失焦），
 * 所以解析结果按「原始串」缓存：命中就不解密。缓存键是 prefs 里的原值而不是 id —— 备份恢复、
 * 别的实例写盘之后原值会变，缓存自动失效，不会把旧值一直喂给 UI。
 */
class InstalledPlugins internal constructor(
    private val prefs: SharedPreferences,
    private val vault: PluginConfigVault,
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        PluginConfigVault.forContext(context),
    )

    /** prefs 键 → (原始串, 解析结果)。原始串一致才算命中，避免缓存盖掉外部写入（备份恢复）。 */
    private val configCache = ConcurrentHashMap<String, CachedConfig>()

    private class CachedConfig(val raw: String, val parsed: Map<String, String>)

    /** id → manifest JSON as installed. */
    fun all(): Map<String, PluginManifestFile> {
        val raw = prefs.getString(KEY, "{}").orEmpty()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().mapNotNull { id ->
                val text = root.optString(id).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PluginManifestFile.parse(text).getOrNull()?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun get(id: String): PluginManifestFile? = all()[id]

    fun put(manifest: PluginManifestFile, rawJson: String) = putRaw(manifest.id, rawJson)

    /** 原始清单 JSON（未解析）。安装器要把它原样写回，不能经过解析再序列化。 */
    fun raw(id: String): String? = rawRoot().optString(id).takeIf { it.isNotBlank() }

    private fun putRaw(id: String, rawJson: String) {
        val root = rawRoot()
        root.put(id, rawJson)
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private fun rawRoot(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY, "{}").orEmpty()) }.getOrDefault(JSONObject())

    /**
     * 安装器的窄缝视图：id → 原始清单 JSON。
     *
     * 抽成 [PackageRegistry] 是为了让 [PluginPackageService] 的单测能观察「注册表什么时候
     * 被写」——事务回滚的正确性依赖这个顺序（文件先到位，注册表最后写）。
     */
    fun registry(): PackageRegistry = object : PackageRegistry {
        override fun ids(): Set<String> = all().keys
        override fun raw(id: String): String? = this@InstalledPlugins.raw(id)
        override fun put(id: String, rawJson: String) = putRaw(id, rawJson)
        override fun remove(id: String) = this@InstalledPlugins.remove(id)
    }

    fun remove(id: String) {
        val root = rawRoot()
        root.remove(id)
        prefs.edit().putString(KEY, root.toString()).apply()
        configPrefs.edit().remove(configKey(id)).apply()
        configCache.remove(configKey(id))
    }

    fun config(id: String): Map<String, String> {
        val key = configKey(id)
        val raw = configPrefs.getString(key, "").orEmpty()
        configCache[key]?.let { if (it.raw == raw) return it.parsed }
        if (raw.isBlank()) return remember(key, raw, emptyMap())
        if (PluginConfigVault.isSealed(raw)) {
            // 密文解不开（换机 / 清数据后密钥没了、密文被改）→ 没有配置，不回退明文、不崩。
            return remember(key, raw, vault.open(raw)?.let(::parseConfig).orEmpty())
        }
        // 旧版本写的明文：先按原样返回（升级后行为不变），再就地迁移成密文。
        val parsed = parseConfig(raw) ?: return remember(key, raw, emptyMap())
        writeConfig(id, parsed)
        return parsed
    }

    private fun remember(key: String, raw: String, parsed: Map<String, String>): Map<String, String> {
        configCache[key] = CachedConfig(raw, parsed)
        return parsed
    }

    fun setConfig(id: String, key: String, value: String) {
        val current = config(id).toMutableMap()
        current[key] = value
        writeConfig(id, current)
    }

    /** Snapshot used by the backup system: manifests plus their config values. */
    fun export(): JSONObject {
        val root = JSONObject()
        all().forEach { (id, _) ->
            val entry = JSONObject()
            entry.put("config", JSONObject(config(id) as Map<*, *>))
            root.put(id, entry)
        }
        return root
    }

    fun import(json: JSONObject) {
        json.keys().forEach { id ->
            val config = json.optJSONObject(id)?.optJSONObject("config") ?: return@forEach
            writeConfig(id, config.keys().asSequence().associateWith { config.optString(it) })
        }
    }

    /** 解析失败返回 null（旧行为是当成空配置）；成功则拿到 id → 值。 */
    private fun parseConfig(raw: String): Map<String, String>? = runCatching {
        val o = JSONObject(raw)
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrNull()

    /** 写路径只写密文：加密不可用时宁可不写，也不把明文落盘。 */
    private fun writeConfig(id: String, values: Map<String, String>) {
        val sealed = runCatching { vault.seal(JSONObject(values as Map<*, *>).toString()) }.getOrNull() ?: return
        val key = configKey(id)
        configPrefs.edit().putString(key, sealed).apply()
        // 写成功才更新缓存：seal 失败时磁盘没变，缓存也不该变。
        configCache[key] = CachedConfig(sealed, values.toMap())
    }

    private fun configKey(id: String): String = CONFIG_PREFIX + id

    private val configPrefs: SharedPreferences
        get() = prefs

    private companion object {
        const val PREFS_NAME = "dsh.plugin.packages"
        const val KEY = "installed"
        const val CONFIG_PREFIX = "values."
    }
}
