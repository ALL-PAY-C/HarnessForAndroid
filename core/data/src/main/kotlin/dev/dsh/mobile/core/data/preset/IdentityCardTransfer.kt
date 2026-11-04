package dev.dsh.mobile.core.data.preset

import dev.dsh.mobile.core.model.IdentityCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 身份卡的剪贴板交换格式（T9.26）：单卡、全量两种颗粒度，字段与 [IdentityCard] 一一对应。
 *
 * 三种外壳都要能读：单对象（带 id）、裸数组、`{"hfaCards":1,"cards":[...]}`。
 * 校验与报错只给 [CardError] 枚举，中文文案由 UI 层（feature:chat）映射资源——
 * 数据层不出现面向用户的字面量，i18n 门禁与语言切换都靠这条线。
 */
object IdentityCardTransfer {

    /** 格式版本；导出的每个对象都带，导入时版本不认识就整体拒绝。 */
    const val SCHEMA: Int = 1

    /** 单个字段（含列表里的每一项）的长度上限。 */
    const val MAX_FIELD: Int = 4000

    /** 一次导入的卡片张数上限，防止误粘一个巨大的 JSON 把 DataStore 撑爆。 */
    const val MAX_CARDS: Int = 200

    private const val CARD_KEY = "hfaCard"
    private const val CARDS_KEY = "hfaCards"
    private const val ID_KEY = "id"
    private const val CARDS_FIELD = "cards"
    private const val NAME_KEY = "name"

    private val TEXT_KEYS = listOf(NAME_KEY, "identity", "tone", "freeform")
    private val LIST_KEYS = listOf("duties", "boundaries", "rules", "examples")

    private val json = Json { ignoreUnknownKeys = true }

    /** 单张卡：{"hfaCard":1,"id":"...","name":"...",...}。 */
    fun encodeCard(id: String, card: IdentityCard): String =
        JsonObject(mapOf(CARD_KEY to JsonPrimitive(SCHEMA), ID_KEY to JsonPrimitive(id)) + fields(card)).toString()

    /** 全量：{"hfaCards":1,"cards":[{"id":"...",...}]}。 */
    fun encodeAll(cards: List<Pair<String, IdentityCard>>): String = JsonObject(
        mapOf(
            CARDS_KEY to JsonPrimitive(SCHEMA),
            CARDS_FIELD to JsonArray(
                cards.map { (id, card) ->
                    JsonObject(mapOf(ID_KEY to JsonPrimitive(id)) + fields(card))
                },
            ),
        ),
    ).toString()

    /** 解析剪贴板文本：整体坏掉给 [CardImport.Failed]，单张坏掉记进 skipped 继续读下一张。 */
    fun decode(text: String): CardImport {
        val raw = text.trim()
        if (raw.isEmpty()) return CardImport.Failed(CardError.Blank)
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return CardImport.Failed(CardError.BadJson)
        val elements = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> when {
                root[CARDS_FIELD] is JsonArray -> {
                    if (!versionOk(root[CARDS_KEY])) return CardImport.Failed(CardError.UnsupportedSchema)
                    (root[CARDS_FIELD] as JsonArray).toList()
                }
                else -> {
                    if (!versionOk(root[CARD_KEY])) return CardImport.Failed(CardError.UnsupportedSchema)
                    listOf(root)
                }
            }
            else -> return CardImport.Failed(CardError.Shape)
        }
        if (elements.isEmpty()) return CardImport.Failed(CardError.EmptyCards)
        if (elements.size > MAX_CARDS) return CardImport.Failed(CardError.TooManyCards)

        val cards = ArrayList<ImportedCard>(elements.size)
        val skipped = ArrayList<CardSkip>(0)
        elements.forEach { element ->
            when (val parsed = parseCard(element)) {
                is Parsed.Card -> cards += parsed.value
                is Parsed.Bad -> skipped += parsed.skip
            }
        }
        if (cards.isEmpty() && skipped.isEmpty()) return CardImport.Failed(CardError.EmptyCards)
        return CardImport.Ok(cards, skipped)
    }

    /**
     * 落库前的 id 分配：入参是 JSON 里的 id（导出时写的就是预设 id），
     * 与已有 id 撞了就依次试 `-2`、`-3`…，绝不覆盖已有预设 / 已有卡。
     */
    fun toPresets(cards: List<ImportedCard>, taken: Set<String>): List<AgentPreset> {
        val used = HashSet(taken)
        return cards.map { imported ->
            val base = slug(imported.id.ifBlank { imported.card.name })
            var id = base
            var n = 2
            while (!used.add(id)) {
                id = base + "-" + n
                n += 1
            }
            AgentPreset(id = id, name = imported.card.name.trim(), card = imported.card)
        }
    }

    private fun fields(card: IdentityCard): Map<String, JsonElement> = mapOf(
        NAME_KEY to JsonPrimitive(card.name),
        "identity" to JsonPrimitive(card.identity),
        "duties" to strings(card.duties),
        "boundaries" to strings(card.boundaries),
        "rules" to strings(card.rules),
        "tone" to JsonPrimitive(card.tone),
        "examples" to strings(card.examples),
        "freeform" to JsonPrimitive(card.freeform),
    )

    private fun strings(items: List<String>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })

    /** 没写版本号 = 老/手写的 JSON，照样接受；写了但不认识就整体拒绝。 */
    private fun versionOk(element: JsonElement?): Boolean {
        if (element == null) return true
        // 版本号允许写成 1 或 "1"。
        val value = element as? JsonPrimitive ?: return false
        return value.content.trim().toIntOrNull() == SCHEMA
    }

    private fun parseCard(element: JsonElement): Parsed {
        val obj = element as? JsonObject ?: return Parsed.Bad(CardSkip(CardError.BadType))
        val text = HashMap<String, String>(4)
        TEXT_KEYS.forEach { key ->
            when (val value = obj[key]) {
                null, is JsonNull -> text[key] = ""
                is JsonPrimitive -> {
                    if (!value.isString) return Parsed.Bad(CardSkip(CardError.BadType, key))
                    if (value.content.length > MAX_FIELD) return Parsed.Bad(CardSkip(CardError.TooLong, key))
                    text[key] = value.content
                }
                else -> return Parsed.Bad(CardSkip(CardError.BadType, key))
            }
        }
        val lists = HashMap<String, List<String>>(4)
        LIST_KEYS.forEach { key ->
            when (val value = obj[key]) {
                null, is JsonNull -> lists[key] = emptyList()
                is JsonArray -> {
                    val items = ArrayList<String>(value.size)
                    value.forEach { item ->
                        val primitive = item as? JsonPrimitive ?: return Parsed.Bad(CardSkip(CardError.BadType, key))
                        if (!primitive.isString) return Parsed.Bad(CardSkip(CardError.BadType, key))
                        if (primitive.content.length > MAX_FIELD) return Parsed.Bad(CardSkip(CardError.TooLong, key))
                        items += primitive.content
                    }
                    lists[key] = items
                }
                else -> return Parsed.Bad(CardSkip(CardError.BadType, key))
            }
        }
        val name = text.getValue(NAME_KEY).trim()
        if (name.isEmpty()) return Parsed.Bad(CardSkip(CardError.MissingName, NAME_KEY, name))
        val card = IdentityCard(
            name = text.getValue(NAME_KEY),
            identity = text.getValue("identity"),
            duties = lists.getValue("duties"),
            boundaries = lists.getValue("boundaries"),
            rules = lists.getValue("rules"),
            tone = text.getValue("tone"),
            examples = lists.getValue("examples"),
            freeform = text.getValue("freeform"),
        )
        return Parsed.Card(ImportedCard(id = idOf(obj), card = card))
    }

    private fun idOf(obj: JsonObject): String {
        val value = obj[ID_KEY] as? JsonPrimitive ?: return ""
        return if (value.isString) value.content.trim().take(120) else ""
    }

    /** id 只留 ASCII 安全字符；中文卡名留不下就退回 "card"，唯一性交给 [toPresets]。 */
    private fun slug(raw: String): String {
        val kept = raw.trim().map { ch -> if (ch.code < 128 && ch.isLetterOrDigit()) ch else '-' }.joinToString("")
        return kept.trim('-').lowercase().ifBlank { "card" }.take(64)
    }

    private sealed interface Parsed {
        data class Card(val value: ImportedCard) : Parsed
        data class Bad(val skip: CardSkip) : Parsed
    }
}

/** 导入失败的整包原因；中文文案在 UI 层按资源映射。 */
enum class CardError { Blank, BadJson, Shape, UnsupportedSchema, TooManyCards, EmptyCards, MissingName, TooLong, BadType }

/** 一张读出来的卡：id 来自 JSON（可能是空串），落库前由 [IdentityCardTransfer.toPresets] 重新分配。 */
data class ImportedCard(val id: String, val card: IdentityCard)

/** 单张被跳过的卡：原因 + 出事的字段 + 卡名（可能为空，用于提示）。 */
data class CardSkip(val reason: CardError, val field: String = "", val name: String = "")

sealed interface CardImport {
    data class Ok(val cards: List<ImportedCard>, val skipped: List<CardSkip>) : CardImport
    data class Failed(val reason: CardError) : CardImport
}
