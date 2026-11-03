package dev.dsh.mobile.toolbridge.phone

import org.json.JSONObject

/** sequence 的一步：动作名 + 该动作的完整参数（已合并 action）+ 步后等待。 */
data class PhoneStep(val index: Int, val action: String, val args: JSONObject, val waitAfterMs: Long) {

    /** 还原成 execBlocking 能吃的单条参数 JSON；不改动 args 本体（复制后再加 action）。 */
    fun toJson(): String = JSONObject(args.toString()).put("action", action).toString()
}

sealed interface PhoneStepsResult {
    data class Ok(val steps: List<PhoneStep>) : PhoneStepsResult

    data class Bad(val message: String) : PhoneStepsResult
}

/** 单步结局，只留模型判断下一步需要的字段。 */
data class PhoneStepOutcome(
    val index: Int,
    val action: String,
    val ok: Boolean,
    val code: String,
    val message: String,
    val elapsedMs: Long,
)

/**
 * sequence 的参数校验与结果信封。
 *
 * 白名单是硬约束：不嵌套 sequence（否则步数上限失去意义），也不让 sequence 里跑
 * 读屏 / 截图 / 装应用列表这类不是「动作」的东西（它们单独调更省）。
 */
object PhoneSequence {
    const val MAX_STEPS = 20
    const val MAX_WAIT_AFTER_MS = 3_000L

    /**
     * 可以塞进 sequence 的动作。
     *
     * back/home/recents 是无参全局动作，真实用法就是「点开→返回」这种序列，
     * 所以直接放进白名单（早前注释写着「key 已包含」是错的：key 是按键注入，不是全局动作）。
     */
    val ALLOWED: Set<String> = setOf(
        "tap", "swipe", "scroll", "type", "key", "pinch", "drag", "wait_for", "openapp",
        "back", "home", "recents",
    )

    private val FORBIDDEN: Map<String, String> = mapOf(
        "sequence" to "sequence 不能嵌套 sequence，把步骤摊平",
        "tree" to "sequence 里不读树，先读完再下发动作",
        "readscreen" to "sequence 里不读屏",
        "screenshot" to "sequence 里不截图",
        "apps" to "apps 不是界面动作，单独调",
        "currentapp" to "currentapp 不是界面动作，单独调",
        "apps_raw" to "apps 不是界面动作，单独调",
    )

    /**
     * 解析 steps 数组。任何一项不合格都整体拒（E_BAD_ARGS），不做「跳过坏步」的容忍 ——
     * 少执行一步和报错相比，前者更难排查。
     */
    fun parse(raw: Any?): PhoneStepsResult {
        if (raw == null || raw == JSONObject.NULL) return PhoneStepsResult.Bad("sequence 需要 steps 数组")
        if (raw !is org.json.JSONArray) return PhoneStepsResult.Bad("steps 必须是数组")
        if (raw.length() == 0) return PhoneStepsResult.Bad("steps 不能为空")
        if (raw.length() > MAX_STEPS) {
            return PhoneStepsResult.Bad("steps 最多 " + MAX_STEPS + " 步，这次给了 " + raw.length() + " 步")
        }
        val steps = ArrayList<PhoneStep>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.opt(i)
            if (item !is JSONObject) return PhoneStepsResult.Bad("第 " + (i + 1) + " 步不是对象")
            val action = item.optString("action", "").trim()
            if (action.isEmpty()) return PhoneStepsResult.Bad("第 " + (i + 1) + " 步缺 action")
            FORBIDDEN[action]?.let { return PhoneStepsResult.Bad("第 " + (i + 1) + " 步：" + it) }
            if (action !in ALLOWED) {
                return PhoneStepsResult.Bad(
                    "第 " + (i + 1) + " 步 action=" + action + " 不在白名单（可用：" +
                        ALLOWED.joinToString("/") + "）",
                )
            }
            val argsRaw = item.opt("args")
            val explicit = when {
                argsRaw == null || argsRaw == JSONObject.NULL -> null
                argsRaw is JSONObject -> argsRaw
                else -> return PhoneStepsResult.Bad("第 " + (i + 1) + " 步 args 必须是对象")
            }
            // 宽容层：动作参数既可以写在 args 里，也可以直接写在步骤顶层
            //（模型常写成 {"action":"tap","target":{…}}，写死只认 args 会得到一条看不懂的
            //「tap 需要 query 或坐标」）。两处都有时 args 优先。
            val args = JSONObject()
            for (key in item.keys()) {
                if (key == "action" || key == "args" || key == "waitAfterMs") continue
                args.put(key, item.opt(key))
            }
            if (explicit != null) {
                for (key in explicit.keys()) args.put(key, explicit.opt(key))
            }
            val waitAfter = item.optLong("waitAfterMs", 0L).coerceIn(0L, MAX_WAIT_AFTER_MS)
            steps.add(PhoneStep(i + 1, action, args, waitAfter))
        }
        return PhoneStepsResult.Ok(steps)
    }

    /** 结果信封：ok 只表示「没被中断」，逐步成败看 steps。 */
    fun envelope(
        outcomes: List<PhoneStepOutcome>,
        total: Int,
        aborted: Boolean,
        abortCode: String?,
        elapsedMs: Long,
    ): JSONObject {
        val array = org.json.JSONArray()
        for (o in outcomes) {
            array.put(
                JSONObject()
                    .put("index", o.index)
                    .put("action", o.action)
                    .put("ok", o.ok)
                    .put("code", o.code)
                    .put("message", o.message)
                    .put("elapsedMs", o.elapsedMs),
            )
        }
        return JSONObject()
            .put("ok", !aborted)
            .put("completed", outcomes.count { it.ok })
            .put("total", total)
            .put("aborted", aborted)
            .put("abortCode", abortCode ?: JSONObject.NULL)
            .put("steps", array)
            .put("elapsedMs", elapsedMs)
    }
}
