package dev.dsh.mobile.feature.plugins

import org.json.JSONObject

/**
 * 桌面端 DSH 左侧「技能」在 App 里的镜像数据。
 *
 * 来源是 Node 引擎 rpc(`/skill-manager`, `list`)：
 * `{"ok":true,"value":{"entries":[{"name":…,"effectiveEnabled":…}],"projectRoots":[]}}`。
 * 解析全程容错：引擎加字段、插件少写字段、entries 整个缺失都不该让整屏白掉。
 */
data class SkillEntry(
    val name: String,
    val description: String = "",
    val globalEnabled: Boolean = false,
    val projectEnabled: Boolean = false,
    val effectiveEnabled: Boolean = false,
    val updateSupported: Boolean = false,
    val modelInvokable: Boolean = false,
)

/** 缺 entries / 类型不对都返回空表，不抛；没有 name 的条目直接丢（没法开关）。 */
fun parseCatalog(json: JSONObject): List<SkillEntry> {
    val array = json.optJSONArray("entries") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            add(
                SkillEntry(
                    name = name,
                    description = o.optString("description"),
                    globalEnabled = o.optBoolean("globalEnabled"),
                    projectEnabled = o.optBoolean("projectEnabled"),
                    effectiveEnabled = o.optBoolean("effectiveEnabled"),
                    updateSupported = o.optBoolean("updateSupported"),
                    modelInvokable = o.optBoolean("modelInvokable"),
                )
            )
        }
    }
}

/** 裸 JSON 文本入口：坏 JSON 落到 Result.failure，不炸 UI 线程。 */
fun parseCatalogResult(raw: String): Result<List<SkillEntry>> =
    runCatching { parseCatalog(JSONObject(raw)) }

/** rpc 回包既可能是 `{ok,value}` 信封，也可能直接是 value 本身，两种都认。 */
fun catalogOf(rpcResult: JSONObject): JSONObject = rpcResult.optJSONObject("value") ?: rpcResult

/** set-enabled 的请求体；[scope] 取 "global" 或 "project"。 */
fun setEnabledPayload(name: String, scope: String, enabled: Boolean): JSONObject = JSONObject()
    .put("name", name)
    .put("scope", scope)
    .put("enabled", enabled)
