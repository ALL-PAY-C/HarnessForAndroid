package dev.dsh.mobile.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一个可交给 Agent 的包管理工具描述（name / description / JSON-Schema）。 */
data class PackageToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", inputSchema)
}

/**
 * 插件包管理的工具适配层：给 Agent 一份「能读能改」的接口，不碰 core/protocol 的工具注册。
 *
 * 修复流程就是这套工具的自然用法：
 *   1. plugins.inspect（或 plugins.log）读到 lastError / 失败行；
 *   2. 在工作区里改插件源；
 *   3. 构建 / 单测；
 *   4. plugins.repair（或 plugins.install 指定新源）重装。
 *
 * 所有结果都是 JSON：ok 为 false 时 error 一定是原始错误文本，不会被包装成成功。
 */
object PluginPackageTools {

    private fun schema(properties: JSONObject, required: List<String> = emptyList()): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))

    private fun str(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    fun specs(): List<PackageToolSpec> = listOf(
        PackageToolSpec(
            "plugins.list",
            "列出所有插件包及其安装状态、活动版本与最近错误。",
            schema(JSONObject()),
        ),
        PackageToolSpec(
            "plugins.inspect",
            "查看单个插件包：installed/active/path/version/lastError。",
            schema(JSONObject().put("id", str("插件 id")), listOf("id")),
        ),
        PackageToolSpec(
            "plugins.install",
            "从本地路径（zip/tgz/目录）安装插件包，失败回滚且保留旧版本。",
            schema(
                JSONObject()
                    .put("source", str("本地文件或目录的绝对路径"))
                    .put("version", str("可选，期望版本（仅记录，实际以清单为准）")),
                listOf("source"),
            ),
        ),
        PackageToolSpec(
            "plugins.repair",
            "读取最近错误，按当前活动目录（或指定 source）重装同版本；不删用户配置。",
            schema(
                JSONObject()
                    .put("id", str("插件 id"))
                    .put("source", str("可选，重装源目录；缺省用当前活动目录")),
                listOf("id"),
            ),
        ),
        PackageToolSpec(
            "plugins.remove",
            "卸载插件包：文件删除成功才清注册表。",
            schema(JSONObject().put("id", str("插件 id")), listOf("id")),
        ),
        PackageToolSpec(
            "plugins.log",
            "读取安装日志尾部（最多 200 行）。",
            schema(JSONObject().put("limit", JSONObject().put("type", "integer"))),
        ),
    )

    /** 执行一个工具。未知工具与缺参都返回 ok=false，不抛。 */
    suspend fun execute(service: PluginPackageService, name: String, input: JSONObject = JSONObject()): JSONObject =
        when (name) {
            "plugins.list" -> JSONObject()
                .put("ok", true)
                .put("packages", JSONArray(service.listIds().map { service.inspect(it).toJson() }))

            "plugins.inspect" -> {
                val id = input.optString("id").trim()
                if (id.isBlank()) {
                    JSONObject().put("ok", false).put("error", "缺少参数 id")
                } else {
                    JSONObject().put("ok", true).put("package", service.inspect(id).toJson())
                }
            }

            "plugins.install" -> {
                val source = input.optString("source").trim()
                if (source.isBlank()) {
                    JSONObject().put("ok", false).put("error", "缺少参数 source")
                } else {
                    val file = File(source)
                    when {
                        file.isDirectory -> service.installFromDirectory(file).toJson()
                        file.isFile -> service.installArchive(file).toJson()
                        else -> JSONObject().put("ok", false).put("error", "路径不存在：" + source)
                    }
                }
            }

            "plugins.repair" -> {
                val id = input.optString("id").trim()
                if (id.isBlank()) {
                    JSONObject().put("ok", false).put("error", "缺少参数 id")
                } else {
                    val source = input.optString("source").trim()
                    service.repair(id, source.ifBlank { null }?.let { File(it) }).toJson()
                }
            }

            "plugins.remove" -> {
                val id = input.optString("id").trim()
                if (id.isBlank()) {
                    JSONObject().put("ok", false).put("error", "缺少参数 id")
                } else {
                    service.remove(id).toJson()
                }
            }

            "plugins.log" -> {
                val limit = input.optInt("limit", 200).coerceIn(1, 500)
                JSONObject().put("ok", true).put("lines", JSONArray(service.log().takeLast(limit)))
            }

            else -> JSONObject().put("ok", false).put("error", "未知工具：" + name)
        }
}
