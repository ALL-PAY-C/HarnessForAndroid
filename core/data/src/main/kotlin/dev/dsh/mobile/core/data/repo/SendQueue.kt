package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.DshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级发送队列。
 *
 * 为什么不在 ViewModel 里排队：ViewModel 跟着页面走。用户在生成过程中排了两条消息然后按
 * 返回键回会话列表，VM 被清掉，队列跟着蒸发——消息既没发出去、界面上也再看不到。排队是
 * 「我让它晚点发」的承诺，必须活在页面之外。
 *
 * 出队时机同样在这里守：监听 [ChatRepository.turns] 的 running 下降沿。只要这一轮在任何
 * 地方结束了（正常收尾 / 用户点停止 / 群聊多成员收口 / 运行时中断），队列立刻继续。
 *
 * 排队实体、代际、中断让路等核心行为收在 [SendQueueEngine]；这里只做三件事：把
 * [ChatRepository] 收窄成引擎要的两个函数、监听 turns 下降沿触发排空、把快照发布成
 * [StateFlow] 供界面订阅。
 */
class SendQueue(
    private val repo: ChatRepository,
    /** 应用级作用域（AppContainer 的 AppScope）：本轮执行活过页面销毁，见 [submit]。 */
    private val scope: CoroutineScope,
) {

    /** 一条排队待发的消息：文本 + 附件 + 稳定 id（界面 key 与「撤下 / 插队」用）。 */
    data class Item(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
        val images: List<String> = emptyList(),
    )

    /**
     * 每会话「排队/插队发送」失败的错误出口。
     *
     * 与 8c 冻结的语义：
     * - [sendError] 返回某会话的只读 [StateFlow]；null = 无错，非 null = 最近一次失败文案。
     * - 只有 sendNow / drain 的失败写这里（这两条路径的 DshResult 不回传给调用方）；
     *   sendText / cancelAndSend 的失败直接返回 DshResult 给 VM，不重复发事件。
     * - 每次新尝试（[engine] sendItem 开头）先清掉该会话旧错误；失败再由 onSendError 重写，
     *   成功/新尝试后旧错误不残留。
     * - 会话被删（[remove]）时一并清空，防止重进 VM 读到陈旧错误。
     * - 并发安全：每会话独立 [MutableStateFlow]，写入是原子 value 赋值，无读改写竞态；
     *   dismiss（clearSendError 置 null）后 StateFlow 不再重放旧错误。
     */
    private val sendErrors = ConcurrentHashMap<String, MutableStateFlow<String?>>()

    private fun errorFlow(sessionId: String): MutableStateFlow<String?> =
        sendErrors.computeIfAbsent(sessionId) { MutableStateFlow(null) }

    /** 每会话「排队/插队发送」最近一次失败文案；null = 无错。dismiss 时调 [clearSendError]。 */
    fun sendError(sessionId: String): StateFlow<String?> = errorFlow(sessionId)

    /** 撤掉某会话的错误（横幅 dismiss / 新尝试开始时用）。清除后 StateFlow 不重放旧错误。 */
    fun clearSendError(sessionId: String) {
        errorFlow(sessionId).value = null
    }

    private val engine = SendQueueEngine(
        sendItem = { sid, item ->
            // 新尝试先清掉上一轮过期错误；失败再写（sendText/cancelAndSend 的 Err 直接回传 VM，不进此出口）。
            clearSendError(sid)
            // messageId 用队列条目的 id：drop/promote/两个排空入口撞车时也只会落一条，
            // 不会出现「同一条排队消息发两次、两个一模一样的用户气泡」。
            repo.sendMessage(sid, item.text, item.images, messageId = item.id)
                .also { r -> if (r is DshResult.Err) AppLog.w("排队消息发送失败：" + r.message) }
        },
        cancel = { sid -> repo.cancel(sid) },
        onSendError = { sid, message -> errorFlow(sid).value = message },
        onChange = { publish() },
    )

    private val _items = MutableStateFlow<Map<String, List<Item>>>(emptyMap())
    /** 全量快照（按会话分组），界面只取自己那一段。 */
    val items: StateFlow<Map<String, List<Item>>> = _items.asStateFlow()

    init {
        scope.launch {
            var prev: Map<String, Boolean> = repo.turns.value.mapValues { it.value.running }
            repo.turns.collect { turns ->
                val now = turns.mapValues { it.value.running }
                // 只认下降沿：true -> false 才算「这一轮结束了」。
                // 异步排空：drain 会阻塞到整轮生成结束，直接 suspend 会卡住 collector、
                // 让其它会话的下降沿跟着排队（N3）。launch 到独立协程，collector 立即回归。
                now.forEach { (sid, running) ->
                    if (prev[sid] == true && !running) scope.launch { drain(sid) }
                }
                // M4：会话被删后 TurnState 从 map 里消失，下降沿再也触发不到——
                // 队里那几条会永久占位。这里把「消失的会话」的队列整条删掉。
                (prev.keys - now.keys).forEach { sid -> remove(sid) }
                prev = now
            }
        }
    }

    fun of(sessionId: String): List<Item> = engine.of(sessionId)

    /** 入队；返回 false 表示已到上限。 */
    fun enqueue(sessionId: String, text: String, images: List<String>, limit: Int): Boolean =
        engine.enqueue(sessionId, text, images, limit)

    fun drop(sessionId: String, id: String) = engine.drop(sessionId, id)

    /** 插到队首：这一轮结束后第一条就发它。 */
    fun promote(sessionId: String, id: String) = engine.promote(sessionId, id)

    /** 清空会话队列（保留代际：已摘出未发的条目因代际失效被跳过）。 */
    fun clear(sessionId: String) = engine.clear(sessionId)

    /** 删除会话队列条目（会话被删时用）。 */
    private fun remove(sessionId: String) {
        engine.remove(sessionId)
        // 会话没了，错误一并清空：防止重进 VM 读到陈旧错误横幅。
        clearSendError(sessionId)
    }

    /** 直发在飞的会话（T9 闸门：一个会话同时只有一条直发在跑）。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * T9：把「这一轮」交给应用作用域跑 —— 页面销毁只解除 uiState 订阅，不取消这一轮。
     *
     * 返回 false = [exclusive] 且该会话已有直发在飞（调用方按排队处理）；Steer/Insert 传
     * false（它们本来就要打断当前轮，不参与闸门）。块内 Err / 异常一律写进 [sendError]：
     * VM 可能已经销毁，绝不回写它的 error。
     */
    fun submit(
        sessionId: String,
        exclusive: Boolean = true,
        block: suspend () -> DshResult<Unit>,
    ): Boolean {
        if (exclusive && !inFlight.add(sessionId)) return false
        scope.launch {
            try {
                val r = block()
                if (r is DshResult.Err) errorFlow(sessionId).value = r.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.w("发送失败：" + e.message)
                errorFlow(sessionId).value = e.message ?: "发送失败"
            } finally {
                if (exclusive) inFlight.remove(sessionId)
                // 这一轮结束（成功/失败/被 cancel）后继续排空；drain 重入安全。
                drain(sessionId)
            }
        }
        return true
    }

    /** 打断当前这轮并立刻把这一条发出去（其余仍留在队列里）。 */
    suspend fun sendNow(sessionId: String, id: String) = engine.sendNow(sessionId, id)

    /** 排空一个会话的队列。重入安全：同一会话同时只有一个排空循环。 */
    suspend fun drain(sessionId: String) = engine.drain(sessionId)

    /** 直接发一条新消息（不打断）：供 ChatViewModel 空闲发送用，结果交给 error UI 展示。 */
    suspend fun sendText(sessionId: String, text: String, images: List<String> = emptyList()): DshResult<Unit> =
        engine.sendText(sessionId, text, images)

    /** 打断当前这轮并立刻发一条新消息（Steer）：cancel 锁外 + send 锁内，串行不靠 delay。 */
    suspend fun cancelAndSend(sessionId: String, text: String, images: List<String> = emptyList()): DshResult<Unit> =
        engine.cancelAndSend(sessionId, text, images)

    private fun publish() {
        _items.value = engine.snapshot()
    }
}
