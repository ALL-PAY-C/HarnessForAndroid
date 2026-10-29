package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2：思考正文必须真正进 turn.parts（按作者），且与工具调用是两套互不污染的数据源。
 * UI 不显示思考正文只是渲染开关（thoughtsExpanded），数据管线一路保留到这里。
 */
class TurnPartsTest {

    private fun turn(running: Boolean = true) = TurnState(running = running, turnId = "t1")

    @Test
    fun thoughtGoesIntoAuthorPart() {
        val st = turn().applyThought("a1", "阿尔法", "先看报错。")
        assertEquals("思考正文要落进 part.thought", "先看报错。", st.parts.single().thought)
        assertEquals("思考不能混进正文", "", st.parts.single().text)
        assertEquals("思考不能混进工具", 0, st.parts.single().tools.size)
        assertEquals("有 part 时轮级 thought 不再重复记一份", "", st.thought)
    }

    @Test
    fun textAndThoughtStayOnSeparateSources() {
        val st = turn()
            .applyThought("a1", "阿尔法", "想一想")
            .applyText("a1", "阿尔法", "答案是 42")
        val part = st.parts.single()
        assertEquals("想一想", part.thought)
        assertEquals("答案是 42", part.text)
        assertEquals(0, part.tools.size)
    }

    @Test
    fun toolCallIsItsOwnSourceAndOverwritesSameId() {
        val running = ToolCall(id = "c1", title = "shell", status = ToolCall.ToolStatus.InProgress)
        val done = running.copy(status = ToolCall.ToolStatus.Completed, responseJson = "ok")
        val st = turn()
            .applyThought("a1", null, "要跑个命令")
            .applyToolCall("a1", null, running)
            .applyToolCall("a1", null, done)
        val part = st.parts.single()
        assertEquals("同一 call.id 只留一条，状态取最新", 1, part.tools.size)
        assertEquals(ToolCall.ToolStatus.Completed, part.tools.single().status)
        assertEquals("工具不能写进思考", "要跑个命令", part.thought)
        assertEquals("工具不能写进正文", "", part.text)
    }

    @Test
    fun thoughtWithoutAuthorButSinglePartAttachesToIt() {
        val st = turn().applyThought("a1", "阿尔法", "前半段思考").applyThought(null, null, "后半段思考")
        assertEquals("唯一 part 就是唯一发言人，思考归它", "前半段思考后半段思考", st.parts.single().thought)
    }

    @Test
    fun thoughtWithoutAuthorAndNoPartsKeepsTurnLevelPipeline() {
        val st = turn().applyThought(null, null, "单聊思维链")
        assertTrue("没有 part 时没有归属，保留轮级 thought（单聊占位气泡靠它）", st.parts.isEmpty())
        assertEquals("单聊思维链", st.thought)
    }

    @Test
    fun thoughtWithoutAuthorAndMultiplePartsDoesNotGuess() {
        val st = turn()
            .applyText("a1", "阿尔法", "一")
            .applyText("a2", "贝塔", "二")
            .applyThought(null, null, "谁在想？")
        assertEquals("多 part 并存不猜归属，留轮级", "谁在想？", st.thought)
        assertEquals("", st.parts[0].thought)
        assertEquals("", st.parts[1].thought)
    }

    @Test
    fun authorNameArrivingLaterRefreshesPartName() {
        val st = turn().applyThought("a1", null, "思考").applyText("a1", "阿尔法", "正文")
        assertEquals("名字晚到要刷新（ThoughtChunk 不带 name）", "阿尔法", st.parts.single().name)
    }
}
