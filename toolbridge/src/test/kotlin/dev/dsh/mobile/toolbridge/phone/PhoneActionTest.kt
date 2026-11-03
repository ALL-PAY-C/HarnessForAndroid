package dev.dsh.mobile.toolbridge.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4.2 动作侧纯逻辑自测：定位判定（跨代/歧义/祖先上溯）、wait 条件、sequence 白名单与信封、错误码。
 *
 * 不碰 Android 类型 —— 真正的 performAction / 手势派发只能在设备上用 PhoneTestProbe 验，
 * 这里锁住的是最容易写错、又最容易回退的三条判定：E_STALE_SNAPSHOT、E_NODE_AMBIGUOUS、E_WAIT_TIMEOUT。
 */
class PhoneActionTest {

    private fun node(
        seq: Int,
        parentIndex: Int = -1,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        snapshotId: Long = 7L,
    ) = PhoneNode(
        snapshotId = snapshotId,
        seq = seq,
        parentIndex = parentIndex,
        depth = 0,
        className = className,
        packageName = "com.example.app",
        text = text,
        contentDesc = desc,
        viewId = viewId,
        bounds = PhoneBounds(0, 0, 100, 50),
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        checkable = false,
        checked = false,
        selected = false,
        enabled = true,
        focused = false,
        visible = true,
    )

    private fun selector(json: String): PhoneSelector = PhoneSelector.parse(JSONObject(json))!!

    // ── 定位判定 ────────────────────────────────────────────────────────────

    @Test
    fun nodeIdFromOlderGenerationIsStale() {
        val nodes = listOf(node(0), node(1))
        val spec = PhoneTarget.parse(JSONObject().put("nodeId", "7:1"))
        assertEquals(7L, (spec as PhoneTargetSpec.ByNodeId).snapshotId)
        // 界面变了：当前代次 8，nodeId 还写着 7 —— 必须回 StaleSnapshot，且调用方在定位阶段就停手。
        assertEquals(PhoneResolveResult.StaleSnapshot, PhoneTarget.resolve(spec, nodes, 8L))
    }

    @Test
    fun nodeIdResolvesInsideItsGeneration() {
        val nodes = listOf(node(0), node(1, text = "确定"))
        val r = PhoneTarget.resolve(PhoneTarget.parse(JSONObject().put("nodeId", "7:1")), nodes, 7L)
        assertTrue(r is PhoneResolveResult.Hit)
        assertEquals(1, (r as PhoneResolveResult.Hit).index)
        assertEquals("7:1", r.node.nodeId)
    }

    @Test
    fun selectorWithSeveralHitsIsAmbiguousAndCarriesFewCandidates() {
        val nodes = listOf(node(0, text = "确定"), node(1, text = "取消"), node(2, text = "确定"), node(3, text = "确定"))
        val r = PhoneTarget.resolve(PhoneTarget.parse(JSONObject().put("target", JSONObject().put("text", "确定"))), nodes, 7L)
        assertTrue(r is PhoneResolveResult.Ambiguous)
        val a = r as PhoneResolveResult.Ambiguous
        assertEquals(3, a.count)
        assertTrue(a.candidates.size <= 3)
    }

    @Test
    fun selectorIndexDisambiguates() {
        val nodes = listOf(node(0, text = "确定"), node(1, text = "确定"))
        val json = JSONObject().put("target", JSONObject().put("text", "确定").put("index", 1))
        val r = PhoneTarget.resolve(PhoneTarget.parse(json), nodes, 7L)
        assertEquals(1, (r as PhoneResolveResult.Hit).index)
    }

    @Test
    fun emptyTargetIsInvalidNotNone() {
        assertEquals(PhoneTargetSpec.Invalid, PhoneTarget.parse(JSONObject().put("target", JSONObject())))
        assertEquals(PhoneTargetSpec.None, PhoneTarget.parse(JSONObject().put("x", 10).put("y", 20)))
    }

    @Test
    fun nodeIdWinsOverSelector() {
        val json = JSONObject().put("nodeId", "7:3").put("target", JSONObject().put("text", "确定"))
        val spec = PhoneTarget.parse(json)
        assertTrue(spec is PhoneTargetSpec.ByNodeId)
        assertEquals(3, (spec as PhoneTargetSpec.ByNodeId).seq)
    }

    @Test
    fun clickableAncestorIsFoundUpTheChain() {
        // 0 容器(可点) → 1 行 → 2 文本：点文本要先上溯到 0，列表行才点得动。
        val nodes = listOf(
            node(0, clickable = true, className = "android.widget.LinearLayout"),
            node(1, parentIndex = 0),
            node(2, parentIndex = 1, text = "第一行"),
        )
        assertEquals(0, PhoneTarget.clickableAncestorIndex(nodes, 2))
        assertEquals(0, PhoneTarget.clickableAncestorIndex(nodes, 0))
        assertEquals(-1, PhoneTarget.clickableAncestorIndex(listOf(node(0), node(1, parentIndex = 0)), 1))
    }

    @Test
    fun scrollableAncestorIsFoundUpTheChain() {
        val nodes = listOf(
            node(0, scrollable = true, className = "androidx.recyclerview.widget.RecyclerView"),
            node(1, parentIndex = 0),
            node(2, parentIndex = 1, text = "行"),
        )
        assertEquals(0, PhoneTarget.scrollableAncestorIndex(nodes, 2))
        assertNull(PhoneTarget.scrollableAncestorIndex(listOf(node(0), node(1, parentIndex = 0)), 1))
    }

    @Test
    fun treeFingerprintFollowsContent() {
        val a = listOf(node(0, text = "第 1 页"))
        val b = listOf(node(0, text = "第 2 页"))
        assertTrue(PhoneTarget.treeFingerprint(a) != PhoneTarget.treeFingerprint(b))
        assertEquals(PhoneTarget.treeFingerprint(a), PhoneTarget.treeFingerprint(listOf(node(0, text = "第 1 页"))))
    }

    // ── wait_for ────────────────────────────────────────────────────────────

    @Test
    fun waitTimeoutAndPollAreClamped() {
        val parsed = PhoneWait.parse(
            JSONObject().put("cond", JSONObject().put("type", "text_present").put("textContains", "加载完成"))
                .put("timeoutMs", 99_999).put("pollMs", 1),
        )
        assertNotNull(parsed)
        val req = parsed!!
        assertEquals(PhoneWait.MAX_TIMEOUT_MS, req.timeoutMs)
        assertEquals(PhoneWait.MIN_POLL_MS, req.pollMs)
    }

    @Test
    fun waitNeedsKnownCondType() {
        assertNull(PhoneWait.parse(JSONObject()))
        assertNull(PhoneWait.parse(JSONObject().put("cond", JSONObject().put("type", "whatever"))))
        // node_present 必须带非空 selector
        assertNull(PhoneWait.parse(JSONObject().put("cond", JSONObject().put("type", "node_present"))))
        assertNotNull(
            PhoneWait.parse(
                JSONObject().put("cond", JSONObject().put("type", "node_present").put("selector", JSONObject().put("text", "确定"))),
            ),
        )
    }

    @Test
    fun waitSatisfiedByTextPackageAndGeneration() {
        val nodes = listOf(node(0, text = "Loading"), node(1, desc = "已完成"))
        val since = 7L
        assertTrue(
            PhoneWait.satisfied(PhoneWaitCond.TextPresent("已完成"), nodes, "com.example.app", 7L, since),
        )
        // 大小写不敏感
        assertTrue(PhoneWait.satisfied(PhoneWaitCond.TextPresent("loading"), nodes, null, 7L, since))
        assertFalse(PhoneWait.satisfied(PhoneWaitCond.TextPresent("不存在"), nodes, null, 7L, since))
        assertTrue(PhoneWait.satisfied(PhoneWaitCond.PackageIs("com.example.app"), nodes, "com.example.app", 7L, since))
        assertFalse(PhoneWait.satisfied(PhoneWaitCond.PackageIs("com.other"), nodes, "com.example.app", 7L, since))
        // 代次变化：数字变了才算满足
        assertFalse(PhoneWait.satisfied(PhoneWaitCond.GenerationChanged, nodes, null, 7L, since))
        assertTrue(PhoneWait.satisfied(PhoneWaitCond.GenerationChanged, nodes, null, 8L, since))
    }

    @Test
    fun waitAbsentMeansSelectorMatchesNothing() {
        val nodes = listOf(node(0, text = "确定"))
        val s = selector("{\"text\":\"确定\"}")
        assertFalse(PhoneWait.satisfied(PhoneWaitCond.NodeAbsent(s), nodes, null, 7L, 7L))
        assertTrue(PhoneWait.satisfied(PhoneWaitCond.NodeAbsent(s), emptyList(), null, 7L, 7L))
    }

    // ── sequence ────────────────────────────────────────────────────────────

    @Test
    fun sequenceRejectsNestingAndOffWhitelistSteps() {
        val nested = JSONArray().put(JSONObject().put("action", "sequence").put("args", JSONObject()))
        assertTrue(PhoneSequence.parse(nested) is PhoneStepsResult.Bad)
        val weird = JSONArray().put(JSONObject().put("action", "reboot"))
        assertTrue(PhoneSequence.parse(weird) is PhoneStepsResult.Bad)
        assertTrue(PhoneSequence.parse(JSONArray()) is PhoneStepsResult.Bad)
        assertTrue(PhoneSequence.parse(JSONObject()) is PhoneStepsResult.Bad)
        val tooMany = JSONArray()
        repeat(PhoneSequence.MAX_STEPS + 1) { tooMany.put(JSONObject().put("action", "tap").put("args", JSONObject())) }
        assertTrue(PhoneSequence.parse(tooMany) is PhoneStepsResult.Bad)
    }

    @Test
    fun sequenceAcceptsKnownStepsAndClampsWait() {
        val raw = JSONArray()
            .put(JSONObject().put("action", "tap").put("args", JSONObject().put("x", 10).put("y", 20)))
            .put(JSONObject().put("action", "wait_for").put("args", JSONObject()).put("waitAfterMs", 99_999))
        val seq = PhoneSequence.parse(raw)
        assertTrue(seq is PhoneStepsResult.Ok)
        val steps = (seq as PhoneStepsResult.Ok).steps
        assertEquals(2, steps.size)
        assertEquals(1, steps[0].index)
        assertEquals(PhoneSequence.MAX_WAIT_AFTER_MS, steps[1].waitAfterMs)
        // toJson 只补 action，不改 args 本体
        assertTrue(steps[0].toJson().contains("\"action\":\"tap\""))
        assertEquals(0, steps[0].args.length() - 2)
    }

    @Test
    fun sequenceEnvelopeCarriesAbortStepNumber() {
        val outcomes = listOf(
            PhoneStepOutcome(1, "tap", true, "", "", 12L),
            PhoneStepOutcome(2, "type", false, PhoneErrorCode.ActionRejected.code, "输入框拒绝", 30L),
        )
        val env = PhoneSequence.envelope(outcomes, 3, aborted = true, abortCode = PhoneErrorCode.ActionRejected.code, elapsedMs = 42L)
        assertEquals(false, env.getBoolean("ok"))
        assertEquals(1, env.getInt("completed"))
        assertEquals(3, env.getInt("total"))
        assertEquals(true, env.getBoolean("aborted"))
        assertEquals("E_ACTION_REJECTED", env.getString("abortCode"))
        val steps = env.getJSONArray("steps")
        assertEquals(2, steps.getJSONObject(1).getInt("index"))
        assertEquals("type", steps.getJSONObject(1).getString("action"))
    }

    // ── 错误码 ──────────────────────────────────────────────────────────────

    @Test
    fun actionErrorCodesKeepRetryability() {
        // 重试一次是安全的：等超时、跨代重读、没有焦点的输入框。
        assertTrue(PhoneErrorCode.WaitTimeout.retryable)
        assertTrue(PhoneErrorCode.StaleSnapshot.retryable)
        assertTrue(PhoneErrorCode.NoFocusedEditable.retryable)
        // 重试会把副作用再来一遍，或本来就是确定性失败。
        assertFalse(PhoneErrorCode.NodeAmbiguous.retryable)
        assertFalse(PhoneErrorCode.ActionRejected.retryable)
        assertFalse(PhoneErrorCode.GestureRejected.retryable)
        assertFalse(PhoneErrorCode.NodeNoImeAction.retryable)
        assertEquals("E_WAIT_TIMEOUT", PhoneErrorCode.WaitTimeout.code)
        assertEquals("E_NODE_AMBIGUOUS", PhoneErrorCode.NodeAmbiguous.code)
        assertEquals("E_STALE_SNAPSHOT", PhoneErrorCode.StaleSnapshot.code)
    }

    @Test
    fun errorPayloadCarriesEffectLandedOnlyWhenKnown() {
        val plain = phoneErrorJson(PhoneErrorCode.StaleSnapshot, "旧快照")
        assertFalse(plain.has("effectLanded"))
        assertEquals(false, plain.getBoolean("ok"))
        assertEquals("E_STALE_SNAPSHOT", plain.getString("code"))
        val landed = phoneErrorJson(PhoneErrorCode.ActionRejected, "写了一半", effectLanded = true)
        assertTrue(landed.getBoolean("effectLanded"))
        val notLanded = phoneErrorJson(PhoneErrorCode.ActionRejected, "没派发", effectLanded = false)
        assertFalse(notLanded.getBoolean("effectLanded"))
    }

    @Test
    fun selectorsCollapseToSingleRowBeforeReportingAmbiguous() {
        // 一行里标题 + 摘要都含 "Network"，且行容器可点：这是「点一下该行」而不是歧义。
        val nodes = listOf(
            node(0, className = "android.widget.LinearLayout", clickable = true),
            node(1, parentIndex = 0, text = "Network & internet"),
            node(2, parentIndex = 0, text = "Network, Wi-Fi, mobile"),
        )
        val spec = PhoneTargetSpec.BySelector(PhoneSelector(textContains = "Network"))
        val r = PhoneTarget.resolve(spec, nodes, 7L)
        assertTrue(r is PhoneResolveResult.Hit)
        assertTrue((r as PhoneResolveResult.Hit).index == 1)
    }

    @Test
    fun exactTextWinsOverContainsAcrossRows() {
        // 两行都命中，但只有一行 text 与查询全等 → 选它，不报歧义。
        val nodes = listOf(
            node(0, className = "android.widget.LinearLayout", clickable = true),
            node(1, parentIndex = 0, text = "Network"),
            node(2, className = "android.widget.LinearLayout", clickable = true),
            node(3, parentIndex = 2, text = "Network & internet"),
        )
        val r = PhoneTarget.resolve(PhoneTargetSpec.BySelector(PhoneSelector(textContains = "Network")), nodes, 7L)
        assertTrue(r is PhoneResolveResult.Hit)
        assertTrue((r as PhoneResolveResult.Hit).index == 1)
    }

    @Test
    fun genuinelyDifferentRowsStayAmbiguous() {
        val nodes = listOf(
            node(0, className = "android.widget.LinearLayout", clickable = true),
            node(1, parentIndex = 0, text = "Battery usage"),
            node(2, className = "android.widget.LinearLayout", clickable = true),
            node(3, parentIndex = 2, text = "Battery saver"),
        )
        val r = PhoneTarget.resolve(PhoneTargetSpec.BySelector(PhoneSelector(textContains = "Battery")), nodes, 7L)
        assertTrue(r is PhoneResolveResult.Ambiguous)
    }

    @Test
    fun sequenceAcceptsGlobalActionsAndStepLevelArgs() {
        val raw = JSONArray()
            .put(JSONObject().put("action", "back"))
            .put(JSONObject().put("action", "tap").put("target", JSONObject().put("text", "Network")))
            .put(
                JSONObject().put("action", "tap")
                    .put("args", JSONObject().put("x", 10).put("y", 20))
                    .put("target", JSONObject().put("text", "ignored")),
            )
        val parsed = PhoneSequence.parse(raw)
        assertTrue(parsed is PhoneStepsResult.Ok)
        val steps = (parsed as PhoneStepsResult.Ok).steps
        assertEquals(3, steps.size)
        // 顶层 target 被并进 args；args 里同名键优先（这里是两套键并存，target 与 x/y 都在）。
        val tapArgs = JSONObject(steps[1].toJson())
        assertEquals("Network", tapArgs.getJSONObject("target").getString("text"))
        val both = JSONObject(steps[2].toJson())
        assertEquals(10, both.getInt("x"))
        assertEquals("ignored", both.getJSONObject("target").getString("text"))
    }
}
