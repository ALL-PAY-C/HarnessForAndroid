package dev.dsh.mobile.core.protocol.context

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.context.WarmLayerStore
import dev.dsh.mobile.core.protocol.AgentThreads
import dev.dsh.mobile.core.protocol.CompactResult
import dev.dsh.mobile.core.protocol.CompactionPlan
import dev.dsh.mobile.core.protocol.ContextBudget
import dev.dsh.mobile.core.protocol.ContextSummaryStore
import dev.dsh.mobile.core.protocol.LlmConfig
import dev.dsh.mobile.core.protocol.SessionProjections
import dev.dsh.mobile.core.protocol.tool.contentOrNullSafe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.json.JSONObject
import java.io.IOException

/**
 * 上下文压缩 / 投影（T7.1b 从 LocalEngineTransport 拆出，纯搬移，语义逐字未改）。
 *
 * 三件事：超预算的提前摘要（装配前压一次）、/compact 真入口、模型不可用时的本地节选兜底。
 * 摘要「有则更好」：模型挂了就退回本地节选，绝不能让一次摘要失败挡住整轮对话。
 *
 * 依赖全注入：线程与投影、摘要落盘、窗口来源、历史、模型配置、引擎 RPC，
 * 以及传输层那条上游往返（不发第二次请求、不自建 HTTP）。
 */
internal class Compaction(
    /** 每 Agent 线程：上下文装配与 compactionPrompt 都在这里。 */
    private val threads: AgentThreads,
    /** 摘要持久化：宿主没注入时是 [ContextSummaryStore.None]。 */
    private val summaryStore: ContextSummaryStore,
    /** 会话投影：压缩后 UI 直接 collect。 */
    private val projections: SessionProjections,
    /** 本会话模型的上下文窗口（tokens）。 */
    private val contextWindowProvider: suspend (String) -> Int,
    private val historyProvider: suspend (String) -> List<ChatMessage>,
    private val configProvider: suspend (String) -> LlmConfig?,
    /** 引擎通用 RPC：/commands compact 与桌面同一实现，引擎不在就走本地。 */
    private val engineRpc: (suspend (String, String, JSONObject) -> JSONObject)?,
    /** 一次上游往返（含网关自举与引擎路由）；压缩只借用，不自建 HTTP。 */
    private val postOnce: suspend (LlmConfig, JsonObject) -> JsonObject,
    /** 上游上报的上下文占用（prompt+completion tokens）；从没上报过返回 null。 */
    private val usedTokensOf: (String) -> Int?,
    /**
     * 压缩真发生了（摘要已落盘并投影）时回调。远端 transport 靠它知道「历史已被替换，
     * 身份文本随之蒸发」，于是下一轮重注入。默认空实现 = 纯记账，不惊动任何人。
     */
    private val onCompacted: suspend (String) -> Unit = {},
    /** 温层读写（T9.8 S2）：宿主不注入 = 不刷温层，行为与 S1 完全一致。 */
    private val warmStore: WarmLayerStore? = null,
    /** 温层输入历史（带 messages.rowid）；不注入就当宿主没有温层输入。 */
    private val warmHistoryProvider: (suspend (String) -> List<WarmMessage>)? = null,
    /** 温层时钟：updatedAt 盖章（单测固定值即可断言）。 */
    private val warmClock: () -> Long = System::currentTimeMillis,
) {
    /** 每会话已摘要掉的历史条数：超预算时别每轮重复压同一段（省一次 LLM 请求）。 */
    private val summarizedCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    internal suspend fun windowTokens(sessionId: String): Int = ContextBudget.windowOr(
        runCatching { contextWindowProvider(sessionId) }
            .getOrDefault(ContextBudget.DEFAULT_WINDOW_TOKENS),
    )

    /**
     * 装配前先压一次：超预算就把最老若干轮交 LLM 生成结构化摘要（角色/任务/已决定/未完成/关键路径）。
     *
     * 摘要是「有则更好」：模型不可用就退回本地节选，绝不能让一次摘要失败挡住整轮对话。
     * 返回真正生效的摘要（没压缩返回 null）。
     */
    internal suspend fun maybeSummarize(
        sessionId: String,
        cfg: LlmConfig?,
        budgetChars: Int,
        messages: List<JsonObject>,
    ): String? {
        if (messages.size < 2) return null
        // 插件共存（S3）：插件已经把历史压成 checkpoint 时，本地不再花一次模型调用，也不另生成温层
        // —— 装配以温层为准，两套内容会互相打架，插件那份才是插件侧的真实记忆。
        val checkpoint = WarmAssembly.pluginCheckpoint(messages)
        if (checkpoint != null) {
            absorbCheckpoint(sessionId, checkpoint)
            return null
        }
        val system = messages.first()["content"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        val userMsg = messages.takeLast(1)
        val history = messages.drop(1).dropLast(1)
        val plan = threads.planCompaction(sessionId, budgetChars, system, history, userMsg)
        if (plan == null) {
            // 没到压缩线但轮次够了：只刷温层（S2），真压缩照下面的原逻辑走，这里不动摘要。
            refreshWarmIfDue(sessionId, budgetChars)
            return null
        }
        // 预算按新旧消息的字符数判定，重跑一轮还会得到同一个计划；已经压过这一段就不再花一次 LLM 请求。
        if (threads.summary(sessionId).isNotEmpty() && plan.dropped.size <= (summarizedCount[sessionId] ?: 0)) return null
        val summary = if (cfg == null) {
            ""
        } else {
            runCatching { summarizeDropped(cfg, threads.compactionPrompt(plan)) }
                .onFailure { AppLog.w("上下文摘要失败，退回本地节选：" + (it.message ?: it.javaClass.simpleName)) }
                .getOrDefault("")
                .trim()
        }
        val text = summary.ifBlank { localDigest(plan) }.trim()
        if (text.isEmpty()) return null
        applySummary(sessionId, text, plan.beforeChars)
        summarizedCount[sessionId] = plan.dropped.size
        // 真压缩之后温层必须跟上（S3）：装配以温层为准，水位落后的旧温层会把刚写好的新摘要盖回去。
        // 与摘要用同一批历史（warmHistoryProvider 就是 messages 表）；失败只记日志，不挡本轮对话。
        runCatching { refreshWarm(sessionId, budgetChars) }
            .onFailure { AppLog.w("压缩后温层刷新失败，忽略：" + (it.message ?: it.javaClass.simpleName)) }
        return text
    }

    /** 让模型写结构化摘要：五段固定标题，避免它把历史又复述一遍。 */
    private suspend fun summarizeDropped(cfg: LlmConfig, prompt: String): String {
        val body = buildJsonObject {
            put("model", cfg.model)
            put("max_tokens", 800)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "你是上下文压缩器。把给你的历史压成结构化摘要，只留后续继续干活必需的信息，" +
                            "不要复述原文，不要加解释。严格用这五段标题输出：角色 / 任务 / 已决定 / 未完成 / 关键文件路径。",
                    )
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }
        val resp = postOnce(cfg, body)
        val content = resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        if (content.isBlank()) throw IOException("摘要为空")
        return content
    }

    /** 摘要三处落点：进程内（装配立即生效）、持久存储（重启后回灌）、会话投影（UI 可见）。 */
    private suspend fun applySummary(sessionId: String, summary: String, beforeChars: Int) {
        threads.setSummary(sessionId, summary)
        runCatching { summaryStore.write(sessionId, summary) }
            .onFailure { AppLog.w("摘要持久化失败：" + (it.message ?: it.javaClass.simpleName)) }
        val before = ContextBudget.tokensOf(beforeChars)
        val after = ContextBudget.tokensOf(summary.length)
        projections.noteCompaction(sessionId, before, after, summary.length)
        projections.noteSummaryChars(sessionId, summary.length)
        runCatching { onCompacted(sessionId) }
            .onFailure { AppLog.w("压缩回调失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /** LLM 不可用时的兜底摘要：只留最近若干条 80 字节选，比整段丢掉强。 */
    private fun localDigest(plan: CompactionPlan): String = buildString {
        append("（模型摘要不可用，以下为本地节选）\n")
        plan.dropped.takeLast(12).forEach { m ->
            val role = m["role"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            val text = m["content"]?.jsonPrimitive?.contentOrNullSafe().orEmpty().replace('\n', ' ').take(80)
            append(role).append(": ").append(text).append("\n")
        }
    }.take(1_200)

    // ------------------------------------------------------------------ 温层（T9.8 S2）

    /**
     * 温层刷新：水位之后的新消息 + 库里旧温层 → 新温层并落盘。
     *
     * 去重水位是 [dev.dsh.mobile.core.model.context.WarmLayer.coveredUntilMessageId]（messages.rowid），
     * 与 dropped.size 无关：温层不会重复处理同一批消息，也不改动 [maybeSummarize] 的既有去重。
     * 返回层的 layer 为 null = 本次跳过（没新消息 / 解析空 / 模型失败），旧温层原样留着。
     */
    internal suspend fun refreshWarm(sessionId: String, budgetChars: Int = 0): WarmRefreshReport {
        val store = warmStore ?: return WarmRefreshReport(null, "当前宿主没有接入温层存储。")
        val previous = runCatching { store.read(sessionId) }.getOrNull()
        val covered = previous?.coveredUntilMessageId ?: 0L
        val history = runCatching { warmHistory(sessionId) }.getOrDefault(emptyList())
        val fresh = history.filter { it.rowId > covered && it.text.isNotBlank() }
        if (fresh.isEmpty()) {
            return WarmRefreshReport(null, "温层已覆盖到 rowid " + covered + "，没有新消息。")
        }
        val cfg = runCatching { configProvider(sessionId) }.getOrNull()
            ?: return WarmRefreshReport(null, "没有可用的模型配置，温层跳过。")
        val budget = if (budgetChars > 0) budgetChars else ContextBudget.budgetChars(windowTokens(sessionId))
        val report = WarmLayerBuilder(callModel = { prompt -> askWarmModel(cfg, prompt) }, clock = warmClock)
            .build(sessionId, fresh, previous, WarmTrigger.warmBudgetChars(budget))
        val layer = report.layer ?: return report
        runCatching { store.write(sessionId, layer) }
            .onFailure { AppLog.w("温层落盘失败：" + (it.message ?: it.javaClass.simpleName)) }
        return report
    }

    /** ≥40 条新消息、且没到压缩线时的温层刷新（判定是纯函数 [WarmTrigger]）。 */
    private suspend fun refreshWarmIfDue(sessionId: String, budgetChars: Int) {
        val store = warmStore ?: return
        if (warmHistoryProvider == null) return
        runCatching {
            val history = warmHistory(sessionId)
            val covered = runCatching { store.read(sessionId) }.getOrNull()?.coveredUntilMessageId ?: 0L
            val turns = history.count { it.rowId > covered }
            val used = history.sumOf { it.text.length }
            if (WarmTrigger.decide(turns, used, budgetChars) != WarmDecision.WARM_ONLY) return@runCatching
            refreshWarm(sessionId, budgetChars)
        }.onFailure { AppLog.w("温层刷新失败，忽略：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /**
     * 插件已压（S3）：把插件给的 checkpoint 文本直接当温层内容落盘，水位取现有历史的最大 rowid。
     *
     * 不调模型、不改插件写好的摘要（摘要归插件管），失败只记日志 —— 插件在场时本地退成纯读者。
     */
    private suspend fun absorbCheckpoint(sessionId: String, checkpoint: String) {
        val store = warmStore ?: return
        runCatching {
            val covered = warmHistory(sessionId).maxOfOrNull { it.rowId } ?: 0L
            val layer = WarmParser.fromCheckpoint(checkpoint, covered, warmClock()) ?: return@runCatching
            store.write(sessionId, layer)
        }.onFailure { AppLog.w("插件 checkpoint 吸收失败，忽略：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /** 温层生成的一次上游往返：与 [summarizeDropped] 同一条路径（不自建 HTTP、不发第二次请求）。 */
    private suspend fun askWarmModel(cfg: LlmConfig, prompt: String): String {
        val body = buildJsonObject {
            put("model", cfg.model)
            put("max_tokens", 1_200)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", WARM_SYSTEM_PROMPT)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }
        val resp = postOnce(cfg, body)
        val content = resp["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        if (content.isBlank()) throw IOException("温层输出为空")
        return content
    }

    private suspend fun warmHistory(sessionId: String): List<WarmMessage> =
        warmHistoryProvider?.invoke(sessionId).orEmpty()

    /**
     * P0-2：/compact 的真入口（UI 的 /compact 命令走这里）。
     *
     * 优先让 :node 引擎的 /commands compact 出手（与桌面同一实现）；引擎不在就本地走一次摘要。
     * 没到压缩线时明确回报「不需要压缩」，不要假装压过。
     *
     * 口径（与顶栏同一套，硬要求）：
     * - 窗口 = [windowTokens]（模型 contextWindow，默认 32k）；
     * - 预算 = 窗口 * [ContextBudget.COMPACT_RATIO]（32k → 25600 tokens，留 20% 给回答）；
     * - 已用优先取上游上报的 prompt+completion（与 ChatUiState.contextUsed 同源，误差 0），
     *   从没上报过才退回字符数 /4 估算。
     *
     * 以前预算取 contextBudgetProvider 的字符数 /4（24000 字符 → 6000 tokens），跟顶栏的 32k
     * 完全两套口径，于是任何会话都回「不需要压缩」——真压缩等于没接。
     */
    // Kotlin 不允许覆写函数再写默认值：默认值只留在 Compactor 接口上，调用方按接口签名用。
    internal suspend fun compact(sessionId: String, budgetTokens: Int): CompactResult {
        val history = runCatching { historyProvider(sessionId) }.getOrDefault(emptyList())
        val messages = history.map { m ->
            buildJsonObject {
                put("role", m.role.name.lowercase())
                put("content", m.text)
            }
        }
        val window = windowTokens(sessionId)
        val budgetTok = if (budgetTokens > 0) budgetTokens else ContextBudget.budgetTokens(window)
        val contentChars = messages.sumOf { m ->
            (m["content"] as? JsonPrimitive)?.content?.length ?: 0
        }
        val reported = usedTokensOf(sessionId)
        val usedTok = reported ?: ContextBudget.tokensOf(contentChars)
        // 上游报的 token 比「字符 /4」更准：它说超了就把字符预算按同一比例收紧，
        // 否则会出现「token 已超预算、字符还没超」→ 又一次空转回「不需要压缩」。
        val planBudgetChars = if (reported != null && reported > budgetTok && contentChars > 0) {
            (contentChars.toLong() * budgetTok / reported).toInt().coerceAtLeast(1)
        } else {
            ContextBudget.charsOf(budgetTok)
        }
        val system = messages.firstOrNull()?.get("content")?.jsonPrimitive?.contentOrNullSafe().orEmpty()
        val plan = threads.planCompaction(sessionId, planBudgetChars, system, messages.drop(1).dropLast(1), messages.takeLast(1))
            ?: return CompactResult(
                compacted = false,
                usedTokens = usedTok,
                budgetTokens = budgetTok,
                windowTokens = window,
                message = "上下文还没到压缩线（已用 " + usedTok + " tokens，预算 " + budgetTok +
                    " tokens，窗口 " + window + "），不需要压缩。",
            )
        val remoteSummary = runCatching {
            val rpc = engineRpc ?: return@runCatching ""
            val items = org.json.JSONArray()
            plan.dropped.forEach { items.put(JSONObject(it.toString())) }
            val payload = JSONObject()
                .put("sessionId", sessionId)
                .put("messages", items)
                .put("options", JSONObject().put("keepRecent", 8).put("maxChars", 1_200))
            val resp = rpc("/commands", "compact", payload)
            ((resp.opt("value") as? JSONObject)?.optString("summary")).orEmpty()
        }.getOrDefault("")
        val cfg = runCatching { configProvider(sessionId) }.getOrNull()
        val summary = remoteSummary.trim().ifBlank {
            if (cfg == null) {
                localDigest(plan)
            } else {
                runCatching { summarizeDropped(cfg, threads.compactionPrompt(plan)) }
                    .onFailure { AppLog.w("compact 摘要失败，退回本地节选：" + (it.message ?: it.javaClass.simpleName)) }
                    .getOrDefault("").trim().ifBlank { localDigest(plan) }
            }
        }
        applySummary(sessionId, summary, plan.beforeChars)
        summarizedCount[sessionId] = plan.dropped.size
        // 压缩后的上下文粗估：原文减掉被摘要掉的部分，加上摘要本身；与「已用」同一套 token 口径。
        val afterTok = ContextBudget.tokensOf((contentChars - plan.beforeChars).coerceAtLeast(0) + summary.length)
        return CompactResult(
            compacted = true,
            summaryChars = summary.length,
            beforeTokens = usedTok,
            afterTokens = afterTok,
            droppedMessages = plan.dropped.size,
            usedTokens = usedTok,
            budgetTokens = budgetTok,
            windowTokens = window,
            message = "已压缩 " + plan.dropped.size + " 条早期历史：" + usedTok + " → " + afterTok +
                " tokens（预算 " + budgetTok + " / 窗口 " + window + "）。",
        )
    }
}
