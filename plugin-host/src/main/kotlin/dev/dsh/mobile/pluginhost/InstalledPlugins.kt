package dev.dsh.mobile.pluginhost

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the host knows about *installed* plugin packages.
 *
 * Kept apart from [PluginStore] (which only remembers enabled flags) so that a
 * package can be listed, configured and removed without ever being loaded.
 * Values are stored as JSON in SharedPreferences: the shape is small, and one
 * atomic blob is easier to snapshot for the backup/rollback path than a schema.
 */
class InstalledPlugins(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dsh.plugin.packages", Context.MODE_PRIVATE)

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
        configPrefs.edit().remove("values.$id").apply()
    }

    fun config(id: String): Map<String, String> {
        val raw = configPrefs.getString("values.$id", "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
    }

    fun setConfig(id: String, key: String, value: String) {
        val current = config(id).toMutableMap()
        current[key] = value
        configPrefs.edit().putString("values.$id", JSONObject(current as Map<*, *>).toString()).apply()
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
            configPrefs.edit()
                .putString("values.$id", config.toString())
                .apply()
        }
    }

    private val configPrefs: SharedPreferences
        get() = prefs

    private companion object {
        const val KEY = "installed"
    }
}
