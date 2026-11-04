package dev.dsh.mobile.core.model.context

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmLayerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val full = WarmLayer(
        primaryGoal = "把 HFA 的 T9.8 做成伪无限上下文",
        hardConstraints = listOf("不加第三方依赖", "不 git commit"),
        rejectedOptions = listOf(WarmNote("拆新会话", "会话 id 会变，UI 有切换痕迹")),
        pitfalls = listOf(Pitfall("gradlew.bat 恒有换行差异", "CRLF", "git add -A -- . ':!gradlew.bat'")),
        keyFiles = listOf(KeyFile("core/data/.../db/Db.kt", 223, "版本号"), KeyFile("README.md", 0, "整文件约定")),
        decisions = listOf(WarmNote("温层独立预算 = budgetChars × 0.25")),
        openTodos = listOf("S2 合并", "S3 装配"),
        currentWork = "S1 数据层",
        nextStep = "写迁移测试",
        coveredUntilMessageId = 42L,
        warmVersion = 3,
        source = WarmSource.PLUGIN,
        updatedAt = 1_700_000_000_000L,
    )

    @Test fun renderFollowsLockedThenPinnedThenSoft() {
        val text = full.render()
        val order = listOf("## 温层", "主要目标：", "硬约束：", "已否决：", "踩坑：", "关键文件：", "决策：", "未完成：", "当前工作：", "下一步：")
        val indices = order.map { section -> text.indexOf(section) }
        indices.forEachIndexed { i, idx ->
            assertTrue("缺少段落：" + order[i] + "\n" + text, idx >= 0)
        }
        assertEquals("段落顺序错了：" + text, indices.sorted(), indices)
    }

    @Test fun renderSkipsEmptySections() {
        val text = WarmLayer(primaryGoal = "只填目标").render()
        assertEquals("## 温层\n主要目标：只填目标", text)
        assertFalse(text.contains("硬约束："))
        assertFalse(text.contains("决策："))
    }

    @Test fun emptyLayerRendersNothing() {
        assertTrue(WarmLayer().isEmpty())
        assertTrue(WarmLayer.EMPTY.isEmpty())
        assertEquals("", WarmLayer.EMPTY.render())
        // 只有水位 / 版本 / 时间戳也不算有内容。
        assertTrue(WarmLayer(coveredUntilMessageId = 9L, warmVersion = 4, updatedAt = 1L).isEmpty())
        assertFalse(full.isEmpty())
    }

    @Test fun keyFileRendersPathWithLine() {
        assertEquals("core/app.kt:12 —— 入口", KeyFile("core/app.kt", 12, "入口").render())
        assertEquals("README.md", KeyFile("README.md", 0).render())
        assertEquals("core/app.kt:12", KeyFile(" core/app.kt ", 12).render())
    }

    @Test fun serializationRoundTripKeepsEveryField() {
        val decoded = json.decodeFromString(WarmLayer.serializer(), json.encodeToString(WarmLayer.serializer(), full))
        assertEquals(full, decoded)
    }

    @Test fun oldJsonWithoutNewFieldsStillDecodes() {
        val legacy = """{"primaryGoal":"旧目标","coveredUntilMessageId":7}"""
        val decoded = json.decodeFromString(WarmLayer.serializer(), legacy)
        assertEquals("旧目标", decoded.primaryGoal)
        assertEquals(7L, decoded.coveredUntilMessageId)
        assertEquals(WarmSource.LOCAL, decoded.source)
        assertEquals(1, decoded.warmVersion)
        assertEquals(emptyList<String>(), decoded.openTodos)
    }

    @Test fun mergeKeysAreNormalised() {
        assertEquals(KeyFile("A.KT", 3).key(), KeyFile(" a.kt", 3, "备注").key())
        assertEquals("症状前四十个字以内", Pitfall("症状前四十个字以内", "x").key())
        assertEquals(WarmNote("同样的话", "理由一").key(), WarmNote(" 同样的话 ", "理由二").key())
    }
}
