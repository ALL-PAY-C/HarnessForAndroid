package dev.dsh.mobile.core.data.preset

import dev.dsh.mobile.core.model.IdentityCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.dsh.mobile.core.model.preset.ImportedCard
import dev.dsh.mobile.core.model.preset.CardImport

/** 身份卡交换格式（T9.26）：导出导入往返保真、三种外壳、坏数据逐字段拒绝、id 冲突不覆盖。 */
class IdentityCardTransferTest {

    private val full = IdentityCard(
        name = "审稿人",
        identity = "你是《冷咖啡》的技术审稿人",
        duties = listOf("挑逻辑漏洞", "给出可执行改法"),
        boundaries = listOf("不改用户的代码风格"),
        rules = listOf("先说结论"),
        tone = "克制、直给",
        examples = listOf("结论：不通过 —— 理由：X 行空指针"),
        freeform = "引用时带上路径与行号。",
    )

    private fun ok(result: CardImport): CardImport.Ok {
        assertTrue("expected Ok but was " + result, result is CardImport.Ok)
        return result as CardImport.Ok
    }

    @Test
    fun singleCardRoundTripsEveryField() {
        val back = ok(IdentityCardTransfer.decode(IdentityCardTransfer.encodeCard("reviewer", full)))
        assertEquals(1, back.cards.size)
        assertEquals("reviewer", back.cards[0].id)
        // 导出导入必须逐字段保真：任何字段漏写都会在这里挂掉。
        assertEquals(full, back.cards[0].card)
        assertTrue(back.skipped.isEmpty())
    }

    @Test
    fun fullExportRoundTripsAllCardsKeepingIds() {
        val all = listOf("reviewer" to full, "writer" to IdentityCard(name = "写手", tone = "冷"))
        val json = IdentityCardTransfer.encodeAll(all)
        assertTrue("全量导出要带版本号", json.contains("\"hfaCards\":1"))
        val back = ok(IdentityCardTransfer.decode(json))
        assertEquals(listOf("reviewer", "writer"), back.cards.map { it.id })
        assertEquals(all.map { it.second }, back.cards.map { it.card })
    }

    @Test
    fun acceptsBareArrayShell() {
        val json = "[{\"name\":\"A\",\"tone\":\"冷\"},{\"name\":\"B\"}]"
        val back = ok(IdentityCardTransfer.decode(json))
        assertEquals(listOf("A", "B"), back.cards.map { it.card.name })
        assertEquals(listOf("", ""), back.cards.map { it.id })
        assertEquals("冷", back.cards[0].card.tone)
    }

    @Test
    fun acceptsCardsEnvelopeOfBareCardsWithoutId() {
        // {cards:[...]} 里的元素不要求带 id / hfaCard，只有整包版本号必须对。
        val json = "{\"hfaCards\":1,\"cards\":[{\"name\":\"A\",\"duties\":[\"x\"]}]}"
        val back = ok(IdentityCardTransfer.decode(json))
        assertEquals(1, back.cards.size)
        assertEquals(listOf("x"), back.cards[0].card.duties)
    }

    @Test
    fun acceptsSingleObjectWithoutSchemaField() {
        // 手写的单卡（没版本号）照样收；版本号只在写了的时候校验。
        val back = ok(IdentityCardTransfer.decode("{\"name\":\"A\",\"freeform\":\"约定\"}"))
        assertEquals("约定", back.cards[0].card.freeform)
        assertEquals("", back.cards[0].id)
    }

    @Test
    fun unsupportedSchemaAndBadJsonAreWholeFailures() {
        val schema = IdentityCardTransfer.decode("{\"hfaCards\":99,\"cards\":[{\"name\":\"A\"}]}")
        assertEquals(CardImport.Failed(CardError.UnsupportedSchema), schema)
        assertEquals(CardImport.Failed(CardError.BadJson), IdentityCardTransfer.decode("{\"name\":"))
        assertEquals(CardImport.Failed(CardError.Blank), IdentityCardTransfer.decode("   "))
        assertEquals(CardImport.Failed(CardError.Shape), IdentityCardTransfer.decode("42"))
    }

    @Test
    fun missingNameIsSkippedWithReasonWhileOthersImport() {
        val json = "{\"hfaCards\":1,\"cards\":[{\"tone\":\"冷\"},{\"name\":\"好卡\"}]}"
        val back = ok(IdentityCardTransfer.decode(json))
        assertEquals(listOf("好卡"), back.cards.map { it.card.name })
        assertEquals(1, back.skipped.size)
        assertEquals(CardError.MissingName, back.skipped[0].reason)
        assertEquals("name", back.skipped[0].field)
    }

    @Test
    fun overlongFieldAndWrongTypeAreRejected() {
        val long = "x".repeat(IdentityCardTransfer.MAX_FIELD + 1)
        val json = "{\"cards\":[{\"name\":\"A\",\"freeform\":\"$long\"},{\"name\":\"B\",\"duties\":[\"$long\"]},{\"name\":\"C\",\"duties\":7}]}"
        val back = ok(IdentityCardTransfer.decode(json))
        assertEquals(listOf(CardError.TooLong, CardError.TooLong, CardError.BadType), back.skipped.map { it.reason })
        assertEquals(listOf("freeform", "duties", "duties"), back.skipped.map { it.field })
    }

    @Test
    fun tooManyCardsFailsAsWholePackage() {
        val json = "[" + List(IdentityCardTransfer.MAX_CARDS + 1) { "{\"name\":\"C$it\"}" }.joinToString(",") + "]"
        assertEquals(CardImport.Failed(CardError.TooManyCards), IdentityCardTransfer.decode(json))
    }

    @Test
    fun idCollisionGetsFreshIdAndNeverOverwrites() {
        val taken = setOf("reviewer", "standard", "default")
        val cards = listOf(ImportedCard("reviewer", full), ImportedCard("reviewer", full.copy(name = "副本")))
        val presets = IdentityCardTransfer.toPresets(cards, taken)
        assertEquals(listOf("reviewer-2", "reviewer-3"), presets.map { it.id })
        assertTrue(presets.none { it.id in taken })
        // 卡内容照原样带过来，名称跟着走；内置标记一律清掉。
        assertEquals(listOf("审稿人", "副本"), presets.map { it.name })
        assertTrue(presets.none { it.builtin })
    }

    @Test
    fun blankOrNonAsciiIdFallsBackToUsableSlug() {
        val presets = IdentityCardTransfer.toPresets(listOf(ImportedCard("", full)), setOf("card"))
        assertEquals(listOf("card-2"), presets.map { it.id })
        val ascii = IdentityCardTransfer.toPresets(listOf(ImportedCard("My Card 01", full)), emptySet())
        assertEquals(listOf("my-card-01"), ascii.map { it.id })
    }
}
