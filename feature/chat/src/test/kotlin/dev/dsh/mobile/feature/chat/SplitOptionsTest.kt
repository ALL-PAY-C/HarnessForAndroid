package dev.dsh.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选项卡片解析自检：模型把 A/B/C 写在正文尾巴上时必须能拆成卡片。 */
class SplitOptionsTest {

    @Test
    fun tailOptionsBecomeCard() {
        val text = "好，下面这段保存成 .html 就能看：```html\n<div>x</div>```\n\n公式 $$\\tau = 0.75 R_0 C$$ 也要对。\n\n请选择下一步：\nA. 继续做第二个页面\nB. 先改配色\nC. 停在这里"
        val (head, options) = splitOptions(text)
        assertEquals(3, options.size)
        assertEquals("继续做第二个页面", options[0].value)
        assertTrue("引导语没被吸收: " + head, !head.contains("请选择下一步"))
        assertTrue("正文丢了: " + head, head.contains("公式"))
    }

    /** 围栏贴在句尾、闭合围栏单独成行时，正文里的选项也必须成卡片（真机踩过）。 */
    @Test
    fun optionsAfterInlineFenceWithOwnLineCloser() {
        val text = "好，下面这段保存成 .html 就能看：```html\n<div>x</div>\n```\n\n" +
            "公式 \$\\tau = 0.75 R_0 C\$ 也要对。\n\n请选择下一步：\nA. 继续做第二个页面\nB. 先改配色\nC. 停在这里"
        val (head, options) = splitOptions(text)
        assertEquals(3, options.size)
        assertTrue("正文丢了: " + head, head.contains("也要对"))
    }
    @Test
    fun optionsWithBracketNoiseStillParse() {
        val text = "结论如下。\n]\nA: 在手机上跑网络连通性诊断\nB: 先重试一次看看\n]"
        val (_, options) = splitOptions(text)
        assertEquals(2, options.size)
    }
}