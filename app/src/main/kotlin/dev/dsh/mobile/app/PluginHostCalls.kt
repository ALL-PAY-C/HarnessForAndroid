package dev.dsh.mobile.app

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.runtimehost.HostCallHandler
import dev.dsh.mobile.runtimehost.NodeEngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Node 引擎 → Kotlin 的反向请求处理器：把宿主已有能力暴露成 JS 可调服务。
 *
 * 引擎侧对应 host/cordis.js 的 llm / credentials 两个服务：
 *   ctx.llm.chat({ messages })        → 走这里的 activeLlmConfig + OpenAI 兼容 /chat/completions
 *   ctx.credentials.get/set/list      → runtime/plugin-credentials.json
 *
 * 为什么不直接在 Node 里存密钥：模型配置与 Key 是 Kotlin 侧的 ModelStore，插件不该另建一份；
 * 反向通道让插件只拿到结果，拿不到存储位置。
 */
internal class PluginHostCalls(
    private val container: AppContainer,
    filesDir: File,
) : HostCallHandler {

    private val credentialFile = File(filesDir, "runtime/plugin-credentials.json")
    private val credentialLock = Any()

    override suspend fun handle(method: String, params: JSONObject): JSONObject = when (method) {
        "llm.chat" -> chat(params)
        "llm.resolve" -> resolve()
        "credentials.get" -> JSONObject().put("value", credentials().get(params.optString("key")) ?: JSONObject.NULL)
        "credentials.set" -> {
            val key = params.optString("key")
            if (key.isEmpty()) throw NodeEngineException("credentials", "credentials.set 需要非空 key")
            credentials().set(key, params.optString("value"))
            JSONObject().put("ok", true)
        }
        "credentials.delete" -> {
            val removed = credentials().delete(params.optString("key"))
            JSONObject().put("ok", true).put("deleted", removed)
        }
        "credentials.list" -> JSONObject().put("keys", JSONArray(credentials().keys()))
        else -> throw NodeEngineException("host", "宿主未实现反向方法: $method")
    }

    // ---- llm ----

    private suspend fun chat(params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val incoming = params.optJSONArray("messages")
        if (incoming == null || incoming.length() == 0) {
            throw NodeEngineException("llm", "llm.chat 需要非空 messages 数组")
        }
        val cfg = container.activeLlmConfig(params.optString("sessionId"))
            ?: throw NodeEngineException("llm", "没有可用的模型配置：先在 Agent 页填 API Key 与模型")
        val model = params.optString("model").ifEmpty { cfg.model }

        val body = JSONObject()
            .put("model", model)
            .put("messages", normalizeMessages(incoming))
            .put("stream", false)
        if (cfg.temperature >= 0f) body.put("temperature", cfg.temperature)
        if (cfg.topP >= 0f && cfg.topP <= 1f) body.put("top_p", cfg.topP)
        val maxTokens = if (params.has("maxTokens")) params.optInt("maxTokens", cfg.maxTokens) else cfg.maxTokens
        if (maxTokens > 0) body.put("max_tokens", maxTokens)
        params.optJSONArray("tools")?.let { body.put("tools", it) }

        val url = URL(cfg.baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
        }
        val started = System.currentTimeMillis()
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw NodeEngineException("llm", "模型网关返回 HTTP $code: " + text.take(400))
            }
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw NodeEngineException("llm", "模型网关返回的不是 JSON: " + text.take(400))
            }
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isEmpty()) {
                throw NodeEngineException("llm", "模型网关没有返回 content: " + text.take(400))
            }
            AppLog.i("PLUGIN_LLM_CHAT model=$model ms=${System.currentTimeMillis() - started}")
            JSONObject()
                .put("content", content)
                .put("model", root.optString("model").ifEmpty { model })
                .also { out -> root.optJSONObject("usage")?.let { out.put("usage", it) } }
        } finally {
            conn.disconnect()
        }
    }

    /** 插件可能传 {role, content} 或 {role, content:[{type,text}]}：后者拼成纯文本，网关不认数组。 */
    private fun normalizeMessages(incoming: JSONArray): JSONArray {
        val out = JSONArray()
        for (index in 0 until incoming.length()) {
            val message = incoming.optJSONObject(index) ?: continue
            val role = message.optString("role").ifEmpty { "user" }
            val content = when (val raw = message.opt("content")) {
                is String -> raw
                is JSONArray -> buildString {
                    for (i in 0 until raw.length()) {
                        val part = raw.optJSONObject(i) ?: continue
                        append(part.optString("text"))
                    }
                }
                null, JSONObject.NULL -> ""
                else -> raw.toString()
            }
            out.put(JSONObject().put("role", role).put("content", content))
        }
        return out
    }

    /** 让插件知道宿主有哪些模型可用、哪个能直接出模型。 */
    private suspend fun resolve(): JSONObject {
        val profiles = container.models.models.first()
        val list = JSONArray()
        for (profile in profiles) {
            list.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("model", profile.model)
                    .put("default", profile.isDefault)
                    .put("ready", profile.apiKey.isNotBlank() && profile.model.isNotBlank()),
            )
        }
        val usable = profiles.firstOrNull { it.apiKey.isNotBlank() && it.model.isNotBlank() }
        return JSONObject().put("model", usable?.model ?: JSONObject.NULL).put("models", list)
    }

    // ---- credentials ----

    private fun credentials() = PluginCredentialStore(credentialFile, credentialLock)
}

/**
 * 插件密钥文件：runtime/plugin-credentials.json（{key: value}）。
 *
 * 刻意不加密、不进备份：它只放插件自己管的第三方 key，和 ModelStore / EndpointSecretStore 分开，
 * 避免插件能读到用户的主 Key；卸载应用即随之消失。
 */
private class PluginCredentialStore(private val file: File, private val lock: Any) {

    fun get(key: String): String? {
        if (key.isEmpty()) return null
        return synchronized(lock) {
            val obj = read()
            if (obj.has(key)) obj.optString(key) else null
        }
    }

    fun set(key: String, value: String) {
        synchronized(lock) {
            val obj = read()
            obj.put(key, value)
            write(obj)
        }
    }

    fun delete(key: String): Boolean = synchronized(lock) {
        val obj = read()
        if (!obj.has(key)) return false
        obj.remove(key)
        write(obj)
        true
    }

    fun keys(): List<String> = synchronized(lock) { read().keys().asSequence().toList() }

    private fun read(): JSONObject {
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText()) }.getOrElse {
            AppLog.w("PLUGIN_CREDENTIALS 读取失败，按空处理: ${it.message}")
            JSONObject()
        }
    }

    private fun write(obj: JSONObject) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(obj.toString())
        }.onFailure { AppLog.w("PLUGIN_CREDENTIALS 写入失败: ${it.message}") }
    }
}
