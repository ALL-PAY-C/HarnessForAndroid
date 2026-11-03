package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.model.AgentRunState
import dev.dsh.mobile.core.model.AgentRuntimeState
import dev.dsh.mobile.core.model.AgentTask
import dev.dsh.mobile.core.model.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

// 群组编排与请求拼装的顶层工具函数。
// 单独成文件：自检单测直接调这些纯函数，不用为了测一句话的分诊去起一个传输实例。

/** 换行常量：群聊文本按行切段时要拼回去。 */
private const val NL = "\n"

/** 思维链可能出现的字段名（各家上游不统一）。 */
private val REASONING_KEYS = listOf("reasoning_content", "reasoning", "thinking", "reasoning_details", "analysis")
private val REASONING_INNER_KEYS = listOf("summary", "text", "content")

/** 群聊最多同时几个成员在跑（设置里的并行度只在这个上限内生效）。 */
internal const val GROUP_MAX_PARALLEL = 3

/** 本机网关的自举重试：连不上才重试，非连接类错误一次就抛。 */
internal const val GATEWAY_ATTEMPTS = 3
internal const val GATEWAY_BACKOFF_MS = 700L

/** 展开面板里贴请求原文的上限：再长就不是给人看的了。 */
internal const val JSON_VIEW_LIMIT = 6_000

internal val CONNECT_HINTS = listOf(
    "failed to connect",
    "connection refused",
    "connect timed out",
    "econnrefused",
    "connection reset",
    "broken pipe",
    "no route to host",
)

/** 「这是一件新活」的动词：命中就不当成追问。 */
private val NEW_TASK_VERBS = listOf(
    "实现", "新增", "加个", "加上", "加一个", "再加", "写个", "写一个", "做一个", "生成", "创建",
    "重构", "部署", "修复", "改造", "设计", "搭建", "执行", "跑一下", "排查", "接入",
)

/** 经理/工人/汇总者的输出预算：不设的话推理模型一轮能写几万字。 */
internal const val PLANNER_MAX_TOKENS = 1_500
internal const val SUMMARIZER_MAX_TOKENS = 4_000
internal const val WORKER_MAX_TOKENS = 3_000
internal const val MAX_GROUP_SPEAKERS = 4
internal const val MAX_AUTOMATON_ROUNDS = 8
internal const val MAX_AUTOMATON_REWORK = 2

/** 僵局窗口：连续几轮「没工具调用 + 短发言 + 没有新任务」就判原地打转。 */
internal const val STALL_WINDOW_ROUNDS = 2

/** 一棒「短发言」的字数线：低于它就算没说出新东西。 */
internal const val STALL_MIN_CHARS = 40

private val AUTOMATON_WORDS = listOf(
    "监督", "验收", "审核", "复核", "打回", "重做", "返工", "自动机", "循环", "直到", "每轮",
    "回合", "接力", "互相", "自动跑", "一直",
    "supervise", "verify", "review", "audit", "until", "loop", "redo", "each round",
)

private val SEQUENTIAL_WORDS = listOf(
    "然后", "接着", "随后", "接下来", "下一步", "依次", "先后", "基于上一步",
    "then", "after that", "based on the previous",
)

/** 明确的顺序依赖：出现「先…再…」这类话就不该并行（并行会互相踩）。 */
private fun hasSequentialDependency(text: String): Boolean {
    val t = text.lowercase()
    if (SEQUENTIAL_WORDS.any { containsToken(t, it) }) return true
    return t.contains("先") && t.contains("再")
}

/**
 * 群聊里模型很容易照着历史里的「[名字] 内容」格式，替其他成员一起作答。
 * 这里按行切掉别人的段落，自己名下的前缀去掉，只留下这个人真正说的话。
 */
internal fun sanitizeGroupText(text: String, ownName: String, roster: List<String>): String {
    val others = roster.filter { it != ownName }.toSet()
    val out = StringBuilder()
    var skip = false
    text.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        val m = BRACKET_SPEAKER.find(line)
        when {
            m == null -> if (!skip) out.append(line).append(NL)
            m.groupValues[1].trim() in others -> skip = true
            else -> {
                skip = false
                out.append(m.groupValues[2]).append(NL)
            }
        }
    }
    return out.toString().trim()
}

private val BRACKET_SPEAKER = Regex("""^\s*\[([^\]]{1,24})]\s*(.*)$""")

/** 缩放长边上限：主流视觉模型都按 1568 左右的长边算 token，再大只是白烧上下文。 */
private const val IMAGE_MAX_EDGE = 1568

/** 单张图片压缩后的字节上限；超过继续降质量，别让一次带图请求把网关顶回去。 */
private const val IMAGE_MAX_BYTES = 3_500_000

/**
 * 本地图片 → data URL。
 * 相机原图动辄 8MB / 4000px：直接 base64 会被网关拒或拖垮请求，
 * 老实现超 8MB 就静默丢图（用户以为发了、模型其实没看到），这里改成先缩放再压 JPEG。
 * 返回 null 表示这张图确实用不了，调用方要把原因写进正文，不能装没事。
 */
internal fun imageDataUrl(path: String): String? {
    val file = java.io.File(path)
    if (!file.isFile) return null
    val pngOrGif = file.extension.lowercase() in setOf("png", "gif")
    val bytes = compressForUpload(file, pngOrGif) ?: return null
    val mime = if (pngOrGif) "image/png" else "image/jpeg"
    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    return "data:" + mime + ";base64," + b64
}

/** 缩边 + 压到 [IMAGE_MAX_BYTES] 以内；解码失败返回 null。 */
private fun compressForUpload(file: java.io.File, keepPng: Boolean): ByteArray? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_MAX_EDGE) sample *= 2
    val decoded = android.graphics.BitmapFactory
        .decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@runCatching null
    val edge = maxOf(decoded.width, decoded.height)
    val bitmap = if (edge > IMAGE_MAX_EDGE) {
        val ratio = IMAGE_MAX_EDGE.toFloat() / edge
        android.graphics.Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        decoded
    }
    val format = if (keepPng) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
    var quality = 85
    var out = encode(bitmap, format, quality)
    while (!keepPng && out.size > IMAGE_MAX_BYTES && quality > 45) {
        quality -= 15
        out = encode(bitmap, format, quality)
    }
    out
}.getOrNull()

private fun encode(bitmap: android.graphics.Bitmap, format: android.graphics.Bitmap.CompressFormat, quality: Int): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    bitmap.compress(format, quality, bos)
    return bos.toByteArray()
}

/**
 * user 消息的 content：纯文本时保持字符串（很多网关只吃字符串），
 * 带图时切成 OpenAI 的 parts 数组，并在正文尾部列出本地路径供 shell 工具读取。
 */
internal fun buildUserContent(userText: String, userImages: List<String>): JsonElement {
    // 单趟处理：每张图只解码一次，同时记下哪张没上去。
    val inlined = ArrayList<Pair<String, String>>(userImages.size)
    val dropped = ArrayList<String>()
    userImages.forEach { path ->
        val dataUrl = imageDataUrl(path)
        if (dataUrl != null) inlined += path to dataUrl else dropped += path
    }
    if (userImages.isNotEmpty()) {
        AppLog.i("user images: sent=" + inlined.size + " dropped=" + dropped.size)
    }
    val text = buildString {
        append(userText)
        if (userImages.isNotEmpty()) {
            if (isNotBlank()) append("\n\n")
            append("附件（本地绝对路径，可用 shell 工具直接读取）：")
            append(userImages.joinToString("、"))
        }
        if (dropped.isNotEmpty()) {
            // 静默丢图是最坑的：用户以为发了图，模型只看到一行路径，于是答非所问。
            append("\n\n这几张图没能随请求发出，本轮看不到它们：")
            append(dropped.joinToString("、"))
        }
    }
    val parts = buildJsonArray {
        if (text.isNotBlank()) {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
        inlined.forEach { (_, dataUrl) ->
            addJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", dataUrl) }
            }
        }
    }
    // 纯文本必须保持字符串形态：不少网关只吃字符串 content，塞 parts 数组会 400。
    val plainOnly = parts.size == 1 && userImages.isEmpty()
    return if (parts.isEmpty() || plainOnly) JsonPrimitive(text) else JsonArray(parts)
}

private fun innerThoughtText(obj: JsonObject): String? {
    for (k in REASONING_INNER_KEYS) {
        val v = obj[k]
        if (v is JsonPrimitive && v.isString) return v.content
    }
    return null
}

/** 从 delta / message 里认出思维链：字段名五选一，值可能是字符串、数组或对象。 */
internal fun reasoningOf(obj: JsonObject?): String? {
    if (obj == null) return null
    for (key in REASONING_KEYS) {
        val v = obj[key] ?: continue
        val text = when (v) {
            is JsonPrimitive -> if (v.isString) v.content else null
            is JsonArray -> v.mapNotNull {
                when (it) {
                    is JsonPrimitive -> if (it.isString) it.content else null
                    is JsonObject -> innerThoughtText(it)
                    else -> null
                }
            }.filter { it.isNotBlank() }.joinToString("")
            is JsonObject -> innerThoughtText(v)
            else -> null
        } ?: continue
        if (text.isNotBlank()) return text
    }
    return null
}

/** 剥掉正文里混着的  thinking 段，返回（可见正文, 思维链）。 */
internal fun splitThinking(content: String): Pair<String, String> {
    if (!content.contains('<')) return content to ""
    val splitter = ThinkSplitter()
    val visible = splitter.feed(content) + splitter.finish()
    return visible to splitter.thoughtText
}

/** 短句 + 不含新活动词 → 当成追问（用来把上一个发言人排到第一位）。 */
internal fun isFollowUp(userText: String): Boolean {
    val t = userText.trim()
    if (t.isEmpty() || t.length >= 30) return false
    if (NEW_TASK_VERBS.any { t.contains(it) }) return false
    return true
}

/** 去掉 @ 段后的公共正文：群里所有人都看到的这部分。 */
internal fun sharedText(userText: String, members: List<GroupMember>): String {
    var s = userText
    members.forEach { if (it.name.isNotBlank()) s = s.replace("@" + it.name, " ") }
    return s.trim()
}

private fun isAsciiWordChar(c: Char): Boolean = c.code < 128 && c.isLetterOrDigit()

/**
 * 词级包含：中文/非 ASCII 直接 contains；纯 ASCII 要求两侧不是字母数字，
 * 否则「Demo」会命中「Demo2」，一句话把两个人都叫起来。
 */
internal fun containsToken(haystack: String, needle: String): Boolean {
    if (needle.isEmpty()) return false
    if (needle.any { it.code >= 128 }) return haystack.contains(needle)
    var i = haystack.indexOf(needle)
    while (i >= 0) {
        val before = if (i == 0) ' ' else haystack[i - 1]
        val after = if (i + needle.length >= haystack.length) ' ' else haystack[i + needle.length]
        if (!isAsciiWordChar(before) && !isAsciiWordChar(after)) return true
        i = haystack.indexOf(needle, i + 1)
    }
    return false
}

/** 名字按分隔符切出来的段：Alpha-DS4.1f-GJ → [Alpha, DS4.1f, GJ]。 */
private fun nicknamesOf(name: String): List<String> {
    val full = name.trim()
    if (full.isEmpty()) return emptyList()
    val parts = full.split('-', '_', '.', ' ', '/', '·')
        .map { it.trim() }
        .filter { it.length >= 2 && !it.all { c -> c.isDigit() } }
    return (listOf(full) + parts).distinct()
}

/** 只属于这个名字的昵称段：同组里有别人也叫 Alpha 时，「Alpha」不算独占。 */
private fun exclusiveNicknames(member: GroupMember, members: List<GroupMember>): List<String> {
    val name = member.name
    val mine = nicknamesOf(name)
    if (mine.isEmpty()) return emptyList()
    val others = members
        .filter { it.id != member.id }
        .flatMap { nicknamesOf(it.name) }
        .map { it.lowercase() }
        .toSet()
    val kept = mine.filterNot { it.lowercase() in others }
    return kept.ifEmpty { listOf(name.trim()) }
}

/** 不带 @ 的点名：只认独占昵称段，避免共有段把一堆人叫起来。 */
internal fun namedMentions(text: String, members: List<GroupMember>): List<GroupMember> {
    if (text.isBlank()) return emptyList()
    val lower = text.lowercase()
    val exclusive = members.associate { it.id to exclusiveNicknames(it, members) }
    return members.filter { m ->
        exclusive[m.id].orEmpty().any { containsToken(lower, it.lowercase()) }
    }
}

private val MENTION_HASH = Regex("""^#(\d{1,2})""")
private const val SEGMENT_TRIM = "，,：:、。"

/**
 * 严格 @ 解析：最长匹配优先，支持 `@名字#2` 指定同名第 k 个实例。
 *
 * 群里允许同一个 agent 被拉进来多次（每个实例都独立跑），它们的名字一样，
 * 所以「@同名」在没写编号时按出现顺序轮流认领，`#2` 才落到第二个实例。
 */
internal fun parseMentions(text: String, members: List<GroupMember>): List<Pair<GroupMember, String>> {
    if (text.isEmpty() || members.isEmpty()) return emptyList()
    // 候选词 = 全名 + 独占昵称，长的排前面：@Alpha-DS4.1f-GJ 不能被 @Alpha 抢先命中。
    val candidates = ArrayList<Pair<String, GroupMember>>()
    members.forEach { m ->
        (nicknamesOf(m.name) + m.name.trim()).filter { it.isNotEmpty() }.forEach { t ->
            candidates += t to m
        }
    }
    candidates.sortByDescending { it.first.length }

    val claimed = HashMap<String, Int>()
    val hits = ArrayList<Triple<Int, GroupMember, Int>>()
    var i = 0
    while (i < text.length) {
        if (text[i] != '@') {
            i++
            continue
        }
        val rest = text.substring(i + 1)
        var token: String? = null
        var member: GroupMember? = null
        for ((t, m) in candidates) {
            if (rest.startsWith(t, ignoreCase = true)) {
                token = t
                member = m
                break
            }
        }
        if (token == null || member == null) {
            i++
            continue
        }
        var end = i + 1 + token.length
        var pick = member
        val same = members.filter { it.name.trim().equals(member.name.trim(), ignoreCase = true) }
        val hash = MENTION_HASH.find(text.substring(end))
        if (hash != null) {
            val k = hash.groupValues[1].toIntOrNull() ?: 1
            end += hash.value.length
            pick = same.getOrNull(k - 1) ?: member
        } else if (same.size > 1) {
            val n = claimed[member.name] ?: 0
            pick = same[n % same.size]
            claimed[member.name] = n + 1
        }
        hits += Triple(i, pick, end)
        i = end
    }
    if (hits.isEmpty()) return emptyList()
    val sorted = hits.sortedBy { it.first }
    return sorted.mapIndexed { idx, hit ->
        val from = hit.third
        val to = if (idx + 1 < sorted.size) sorted[idx + 1].first else text.length
        val segment = if (to > from) text.substring(from, to).trim() else ""
        hit.second to segment.trimStart(*SEGMENT_TRIM.toCharArray())
    }
}

/** 这一轮谁先说：追问时把上一个发言人挪到最前，其余顺序不变。 */
internal fun orderForTurn(
    members: List<GroupMember>,
    userText: String,
    lastSpeakerId: String?,
): List<GroupMember> {
    if (!isFollowUp(userText) || lastSpeakerId == null) return members
    val hit = members.firstOrNull { it.id == lastSpeakerId } ?: return members
    return listOf(hit) + members.filter { it.id != lastSpeakerId }
}

/** 没 @ 也没点名时的兜底：按轮次挑一个人（省 token 的场合用）。 */
internal fun pickSingleResponder(
    plan: GroupPlan,
    userText: String,
    history: List<ChatMessage>,
    lastSpeakerId: String?,
): GroupMember? {
    val members = plan.members
    if (members.isEmpty()) return null
    if (plan.routing == GroupRouting.RoundRobin) {
        val turn = history.count { it.role == ChatMessage.Role.USER }
        return members[turn % members.size]
    }
    if (isFollowUp(userText) && lastSpeakerId != null) {
        members.firstOrNull { it.id == lastSpeakerId }?.let { return it }
    }
    return members.first()
}

/** 把一个成员一起说成一串任务：每人一条，后一条依赖前一条（接力）。 */
private fun chainTasks(
    sessionId: String,
    speakers: List<Pair<GroupMember, String>>,
    shared: String,
    now: Long,
): List<AgentTask> {
    var previous: String? = null
    return speakers.distinctBy { it.first.id }.mapIndexed { idx, pair ->
        val own = digest(pair.second, 300)
        val prompt = if (own.isBlank() || own == shared) {
            "群里正在讨论：" + shared
        } else {
            "群里正在讨论：" + shared + "\n只处理指向你的这部分：" + own
        }
        val hint = if (idx == 0) {
            "你是这一轮第一个发言的。直接给出完整答复，不要写「我的看法」这类开场白。"
        } else {
            "前面成员的发言已经在群历史里。只补充他们没说到、或说错了的部分；" +
                "确实没有可补充的内容时，用一句具体的话指出他们的结论是否成立，不要只回「无补充」。"
        }
        val id = UUID.randomUUID().toString()
        val task = AgentTask(
            id = id,
            sessionId = sessionId,
            agentId = pair.first.id,
            agentName = pair.first.name,
            prompt = prompt,
            dependsOn = listOfNotNull(previous),
            createdAt = now + idx,
            hint = hint,
        )
        previous = id
        task
    }
}

/** 并行分工的统一提示：每个人只干自己那份。 */
private const val PARALLEL_HINT =
    "你们在并行分工：其他成员正在同时处理同一请求的不同部分。" +
        "严格执行你的那份，只做你被点名的那部分，做完就收，" +
        "不要替别人补全，也不要重复别人的方向、不要总结他们的结果。"

/**
 * 并行档没有 @ / 点名时的真分工：把请求按句子切成段轮转分给各成员，每人 prompt 只含自己那段；
 * 句子数少于成员数时退化成角色分工（首人主攻实现、其余独立复核/给不同方案）。
 */
private fun parallelSplitTasks(
    sessionId: String,
    members: List<GroupMember>,
    userText: String,
    shared: String,
    now: Long,
    /** 点名段：@/直呼时每位席位自己那段，命中就用它，不再按句子切。 */
    segments: Map<String, String> = emptyMap(),
): List<AgentTask> {
    if (members.isEmpty()) return emptyList()
    val slices = splitRequest(userText)
    // 席位不设上限：并行档是「每个 Participant.slot 一条」，砍掉就等于有人分不到活。
    // 真正的并发上限由执行池的 maxParallel 管，和任务条数是两回事。
    val total = members.size
    val bySlice = slices.size >= total
    // 座次按 memberId 的 code-unit 升序算，和「上一轮谁先发言」无关：
    // 用发言顺序当 idx 会让同一句话在两次运行里切出不同分片（结果不可复现）。
    val seat = HashMap<String, Int>()
    members.sortedBy { it.id }.forEachIndexed { i, m -> seat.putIfAbsent(m.id, i) }
    return members.mapIndexed { idx, m ->
        val rank = seat[m.id] ?: idx
        val own = segments[m.id]?.trim().orEmpty().ifBlank {
            if (bySlice) (rank until slices.size step total).joinToString(" ") { slices[it] } else ""
        }
        val prompt = when {
            own.isNotBlank() -> "你是第 ${rank + 1}/$total 位，只负责这部分：" + own +
                "；其余部分由其他人并行处理，不要代写、不要重复他们的方向，也不要总结他们的结果。"
            rank == 0 -> "你是第 1/$total 位，主攻实现这部分：" + shared +
                "；其余成员会独立复核并给出不同方案，不要替他们完成复核，也不要总结他们的结果。"
            else -> "你是第 ${rank + 1}/$total 位，独立复核第 1 位的实现并给出不同方案，请求是：" + shared +
                "；不要代写第 1 位的实现，也不要重复他的方向。"
        }
        parallelTask(sessionId, m, idx, prompt, now)
    }
}

private fun parallelTask(
    sessionId: String,
    member: GroupMember,
    idx: Int,
    prompt: String,
    now: Long,
): AgentTask = AgentTask(
    id = UUID.randomUUID().toString(),
    sessionId = sessionId,
    agentId = member.id,
    agentName = member.name,
    prompt = prompt,
    dependsOn = emptyList(),
    createdAt = now + idx,
    hint = PARALLEL_HINT,
)

/** 把用户请求按句子切段；只有一句时再按逗号/顿号切成分句；都切不出来就整句当一段。 */
private fun splitRequest(userText: String): List<String> {
    val t = userText.trim()
    if (t.isEmpty()) return emptyList()
    val sentences = t.split(Regex("""[。！？!?；;\n]+"""))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (sentences.size >= 2) return sentences
    val clauses = sentences.firstOrNull()
        ?.split(Regex("""[，,、：:]"""))
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    return if (clauses.size >= 2) clauses else listOf(t)
}

/**
 * 一轮群聊的编排结果。
 *
 * 以前只回任务列表，超上限的人被 `take(MAX_GROUP_SPEAKERS)` 静默砍掉：既没发言，
 * 也没人知道他被砍了。现在被砍的人进 [deferred]（下一轮优先），并配一条 [notice] 说明。
 */
internal data class GroupRound(
    val tasks: List<AgentTask>,
    /** 本轮没轮到的成员 id，按下一轮该先说的顺序。 */
    val deferred: List<String> = emptyList(),
    /** 给群里看的一句话说明；没有截断时为 null。 */
    val notice: String? = null,
)

/** 上一轮没轮到的成员排到最前（其余保持原序），得到本轮候选顺序。 */
internal fun withPriority(
    members: List<GroupMember>,
    priorityIds: List<String>,
): List<GroupMember> {
    if (priorityIds.isEmpty()) return members
    val first = priorityIds.mapNotNull { id -> members.firstOrNull { it.id == id } }.distinctBy { it.id }
    if (first.isEmpty()) return members
    val firstIds = first.map { it.id }.toSet()
    return first + members.filter { it.id !in firstIds }
}

/** 把一句话拆成「谁干什么」：@ > 点名 > 轮次 > 全员接力；超出上限的人显式排到下一轮。 */
internal fun planGroupRound(
    sessionId: String,
    plan: GroupPlan,
    userText: String,
    history: List<ChatMessage>,
    lastSpeakerId: String? = null,
    now: Long = System.currentTimeMillis(),
    mode: GroupMode = GroupMode.Chain,
    /** 上一轮没轮到的成员：本轮排最前先发言（默认取 [GroupPlan.pendingSpeakerIds]）。 */
    priorityIds: List<String> = plan.pendingSpeakerIds,
): GroupRound {
    if (plan.members.isEmpty()) return GroupRound(emptyList())
    val shared = digest(sharedText(userText, plan.members), 200).ifBlank { digest(userText, 200) }
    val mentioned = parseMentions(userText, plan.members)
    val named = if (mentioned.isNotEmpty()) {
        mentioned
    } else {
        namedMentions(userText, plan.members).map { it to "" }
    }
    // 并行档：>=2 个席位就是「每个 Participant.slot 一条独立任务」，一人一条、互不依赖。
    // 只有两种情形才串行：显式单包（RoundRobin 单人作答）和席位不足 2 个。
    // 点名不算串行理由 —— 点名只是把整句收窄成本人那段，不能让并行档退回接力。
    if (mode == GroupMode.Parallel && plan.routing != GroupRouting.RoundRobin && plan.members.size >= 2) {
        // 席位顺序固定用 plan.members（不做 orderForTurn）：并行档谁拿哪片只看稳定座次。
        val seats = if (named.isNotEmpty()) {
            named.map { it.first }.distinctBy { it.id }
        } else {
            plan.members
        }
        val segments = HashMap<String, String>()
        named.forEach { (member, segment) ->
            if (segment.isNotBlank()) segments.putIfAbsent(member.id, segment)
        }
        return GroupRound(
            parallelSplitTasks(
                sessionId = sessionId,
                members = seats,
                userText = userText,
                shared = shared,
                now = now,
                segments = segments,
            ),
        )
    }
    if (named.isNotEmpty()) return GroupRound(chainTasks(sessionId, named, shared, now))
    if (plan.routing == GroupRouting.RoundRobin) {
        val one = listOfNotNull(pickSingleResponder(plan, userText, history, lastSpeakerId)).map { it to "" }
        return GroupRound(chainTasks(sessionId, one, shared, now))
    }
    // 接力/自动机默认档：本轮最多 MAX_GROUP_SPEAKERS 位，多出来的排下一轮，不再静默丢弃。
    val ordered = withPriority(orderForTurn(plan.members, userText, lastSpeakerId), priorityIds)
    val speakers = if (ordered.size <= MAX_GROUP_SPEAKERS) ordered else ordered.take(MAX_GROUP_SPEAKERS)
    val deferred = ordered.drop(speakers.size)
    val notice = if (deferred.isEmpty()) {
        null
    } else {
        "本轮 " + speakers.size + "/" + ordered.size + " 位发言，其余 " + deferred.size +
            " 位下一轮优先（成员：" + deferred.joinToString("、") { it.name } + "）"
    }
    return GroupRound(
        tasks = chainTasks(sessionId, speakers.map { it to "" }, shared, now),
        deferred = deferred.map { it.id },
        notice = notice,
    )
}

/** 只要任务列表的老入口（点名单包 / 测试用）。 */
internal fun planGroupTasks(
    sessionId: String,
    plan: GroupPlan,
    userText: String,
    history: List<ChatMessage>,
    lastSpeakerId: String? = null,
    now: Long = System.currentTimeMillis(),
    mode: GroupMode = GroupMode.Chain,
): List<AgentTask> = planGroupRound(
    sessionId = sessionId,
    plan = plan,
    userText = userText,
    history = history,
    lastSpeakerId = lastSpeakerId,
    now = now,
    mode = mode,
).tasks

/** 按句子分诊：这一轮该用哪种编排形态。 */
internal fun detectGroupMode(
    userText: String,
    memberCount: Int,
    modeOverride: String = "auto",
): GroupMode {
    if (memberCount < 2) return GroupMode.Chain
    when (modeOverride.trim().lowercase()) {
        "chain" -> return GroupMode.Chain
        "automaton" -> return GroupMode.Automaton
        "parallel" -> return GroupMode.Parallel
        "office" -> return GroupMode.Office
    }
    val t = userText.lowercase()
    if (AUTOMATON_WORDS.any { containsToken(t, it.lowercase()) }) return GroupMode.Automaton
    if (hasSequentialDependency(t)) return GroupMode.Chain
    return GroupMode.Parallel
}

private val CN_DIGITS = mapOf(
    '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
    '六' to 6, '七' to 7, '八' to 8, '九' to 9,
)

/**
 * 用户明确写了几轮就用几轮：「跑三轮」→ 3、「跑 5 轮」→ 5、「跑十二轮」→ 12，
 * 都夹在 [MAX_AUTOMATON_ROUNDS] 以内。没写才回落到默认值。
 */
internal fun explicitRoundCount(userText: String): Int? {
    Regex("""(\d+)\s*(?:轮|回合|遍)""").find(userText)?.let { m ->
        m.groupValues[1].toIntOrNull()?.let { return it }
    }
    Regex("""([一二两三四五六七八九十]{1,3})\s*(?:轮|回合|遍)""").find(userText)?.let { m ->
        val raw = m.groupValues[1]
        if (raw == "十") return 10
        if (raw.length == 1) return CN_DIGITS[raw[0]]
        if (raw[0] == '十') return 10 + (CN_DIGITS[raw[1]] ?: 0)
        if (raw.length >= 2) {
            val tens = CN_DIGITS[raw[0]] ?: return null
            val ones = CN_DIGITS[raw[1]] ?: 0
            return tens * 10 + ones
        }
        return null
    }
    return null
}

/** 自动机最多跑几轮：用户写死的轮数优先，其次「直到/监督/循环」这类话才给长预算。 */
internal fun automatonRounds(userText: String): Int {
    explicitRoundCount(userText)?.let { return it.coerceIn(1, MAX_AUTOMATON_ROUNDS) }
    val looping = listOf(
        "监督", "打回", "重做", "自动机", "循环", "直到", "每轮", "回合", "接力", "互相",
        "自动跑", "一直", "supervise", "until", "loop", "redo", "each round",
    ).any { userText.contains(it, ignoreCase = true) }
    return if (looping) MAX_AUTOMATON_ROUNDS else 1
}

/** 自动机这一棒的结论：只看最后一行，正文里提到「打回」不算；没识别出结论就停，不空转到轮次上限。 */
internal fun automatonVerdict(reviewOut: String): Verdict {
    val last = reviewOut.trim().lines().lastOrNull { it.isNotBlank() } ?: ""
    return when {
        last.contains("打回") -> Verdict.Rework
        last.contains("完成") -> Verdict.Done
        last.contains("继续") -> Verdict.More
        else -> {
            AppLog.w("自动机：验收结论未识别（应为【继续】/【完成】/【打回】），已停止；最后一行=<" + last.take(200) + ">")
            Verdict.Done
        }
    }
}

/**
 * 打回计数的一次推进：验收不过 +1，任一通过轮直接清零。
 *
 * 以前只增不清，于是「跑了十轮、中间打过两次回、后面全过」也会在第 3 次打回时提前收工。
 */
internal fun nextReworkCount(current: Int, verdict: Verdict): Int = when (verdict) {
    Verdict.Rework -> current + 1
    Verdict.More, Verdict.Done -> 0
}

/**
 * 僵局检测：连续 [window] 轮，每轮**所有**发言都没有工具调用、单轮发言都不到 [minChars] 字、
 * 也没有新任务入池 —— 说明只是在原地打转，该停手而不是烧到轮次上限。
 *
 * 放在 [automatonVerdict] 同级：结论由验收者给，僵局由调度方兜底；[Verdict.Done] 优先于僵局。
 */
internal class StallDetector(
    private val window: Int = STALL_WINDOW_ROUNDS,
    private val minChars: Int = STALL_MIN_CHARS,
) {
    private var streak = 0

    /** 判定依据的一句话，给群里看；没连续上时为 null。 */
    var reason: String? = null
        private set

    val stalled: Boolean get() = streak >= window

    /** 记录一轮，返回是否已判僵局。 */
    fun onRound(texts: List<String>, toolCalls: Int = 0, newTasks: Int = 0): Boolean {
        val quiet = texts.isNotEmpty() && toolCalls <= 0 && newTasks <= 0 &&
            texts.all { it.trim().length < minChars }
        streak = if (quiet) streak + 1 else 0
        reason = if (streak == 0) {
            null
        } else {
            "连续 " + streak + " 轮没有工具调用、每轮发言都不到 " + minChars + " 字、也没有新任务"
        }
        return stalled
    }
}

/** 给成员看的「谁在忙」一行：≤200 字符，自己标成（你）。 */
internal fun groupStateLine(
    agents: Map<String, AgentRuntimeState>,
    members: List<GroupMember>,
    selfId: String,
): String {
    val parts = members.map { m ->
        val st = agents[m.id]
        val busy = st != null &&
            st.state != AgentRunState.Idle &&
            st.state != AgentRunState.Done &&
            st.state != AgentRunState.Failed
        when {
            m.id == selfId -> m.name + "(你)"
            busy -> {
                val note = st?.note?.trim()?.take(30).orEmpty()
                if (note.isEmpty()) m.name + "(执行中)" else m.name + "(执行中·" + note + ")"
            }
            else -> m.name + "(空闲)"
        }
    }
    val line = "在场：" + parts.joinToString("、")
    return if (line.length <= 200) line else line.take(199) + "…"
}