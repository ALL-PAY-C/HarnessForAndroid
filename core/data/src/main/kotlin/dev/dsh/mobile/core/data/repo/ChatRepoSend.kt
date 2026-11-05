package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.platform.AppLog
import dev.dsh.mobile.core.platform.DshResult
import dev.dsh.mobile.core.platform.runCatchingDsh
import dev.dsh.mobile.core.data.db.DshDatabase
import dev.dsh.mobile.core.data.db.MESSAGE_TEXT_READ_CAP
import dev.dsh.mobile.core.data.db.MessageEntity
import dev.dsh.mobile.core.data.db.SessionEntity
import dev.dsh.mobile.core.model.ChatMessage
import dev.dsh.mobile.core.model.Participant
import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.core.model.SessionKind
import dev.dsh.mobile.core.model.SessionStatus
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.model.ToolCall
import dev.dsh.mobile.core.protocol.AcpTransport
import dev.dsh.mobile.core.protocol.CompactResult
import dev.dsh.mobile.core.protocol.Compactor
import dev.dsh.mobile.core.model.event.DshEvent
import dev.dsh.mobile.core.protocol.DshTransport
import dev.dsh.mobile.core.protocol.LocalEngineTransport
import dev.dsh.mobile.core.protocol.SessionProjection
import dev.dsh.mobile.core.protocol.SessionProjections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------- 提示词与发送流程

/**
 * T9：本轮 prompt 挂在仓库作用域（应用级），不挂调用方作用域。
 * - 调用方取消只停止 await：不 cancel 远端、不定稿半截（见 [sendMessage] 的取消分支）。
 * - prompt 真实结束时先记 promptFinished，再由 collector 的终态 marker 收口；
 *   若调用方已离开（[detached]）而 marker 未到，这里兜底按真实结束结算完整正文。
 */
internal fun ChatRepository.startTurn(
    sessionId: String,
    turnId: String,
    remoteId: String,
    t: DshTransport,
    text: String,
    images: List<String>,
    isRemote: Boolean,
    detached: AtomicBoolean,
): PromptJob {
    // P2：prompt 子任务带 turnId 记入 promptJobs，cancel 只按 turnId 命中，绝不误杀新轮。
    val job = scope.async {
        try {
            // N-1：只在远端 prompt 即将实际发出时注册屏障。本地模式（LocalEngine 无 turnId 合成 marker）、
            // ensureRemoteSession 补建失败、代际切换都在进入此块前 return，不注册屏障 → 不产生无谓阻塞。
            if (isRemote) barriers.seed(sessionId, turnId)
            t.prompt(remoteId, text, images, turnId)
        } finally {
            // N-1/m00652：prompt 结束（成功/失败/真 caller 取消）都记 promptFinished。
            // 真 caller 取消时远端仍活：promptFinished=true 但 markerConsumed=false → 仍挡新 seed。
            // 本地模式/未注册屏障时 no-op（barriers[sessionId] 不存在或 turnId 不匹配）。
            if (isRemote) barriers.promptFinished(sessionId, turnId)
            // 调用方已离开：本轮真实结束，兜底收口（marker 先到则 markFinalized 幂等 no-op）。
            if (detached.get()) withContext(NonCancellable) { runCatchingDsh { finalizeTurn(sessionId, turnId) } }
        }
    }
    val pj = PromptJob(turnId, job)
    promptJobs[sessionId] = pj
    // 先入表再挂完成回调：job 可能在注册前就已结束，回调仍会执行，remove 不会漏。
    job.invokeOnCompletion { promptJobs.remove(sessionId, pj) }
    return pj
}

/**
 * 本轮以异常收尾：先记失败终态再按当前轮回写 Failed，最后原样重抛给调用方（Err）。
 */
internal suspend fun ChatRepository.failTurn(sessionId: String, turnId: String, gen: Long, e: Throwable): Nothing {
    AppLog.w("prompt failed: ${e.message}")
    // T3：任何异常收尾都是确定性失败终态（断流/网络/协议错误同一口径）。
    barriers.settle(sessionId, turnId, resolveTurnOutcome(completedNormally = true, error = e))
    // 失败收尾：ensure 补建失败/代际切换/网络异常都落这里，seed 的轮次与 Running 状态不挂住。
    // 只有「本轮仍是当前在跑轮」且「连接未切换」才回写 Failed：旧 gen/新轮已起时，状态由新连接接管，不被旧回写覆盖。
    val stillCurrentTurn = ledger.current(sessionId)?.turnId == turnId
    // 本轮一个字都没产出时（远端会话补建失败、代际切换、网关异常）finalizeTurn 不落气泡，
    // 界面就只剩「转圈结束、什么都没有」。失败必须在会话里看得见。
    val hadContent = ledger.current(sessionId)?.let {
        it.text.isNotBlank() || it.parts.isNotEmpty() || it.tools.isNotEmpty()
    } ?: false
    // 先落气泡再结算：finalizeTurn 里的幂等/代际判定不该影响这条失败写回。
    if (!hadContent) persistFailureBubble(sessionId, e, turnId)
    finalizeTurn(sessionId, turnId)
    if (stillCurrentTurn && sessionIds.isCurrent(gen)) setStatus(sessionId, SessionStatus.Failed)
    throw e
}

/**
 * 本轮被打断（非调用方离开）：30min 超时是确定性失败终态；用户 cancel 已由 [cancel] 结算。
 * 抽出来是为了让 [sendMessage] 的取消分支只表达「调用方离开不做收尾」。
 */
internal suspend fun ChatRepository.settleInterruptedTurn(
    sessionId: String,
    turnId: String,
    gen: Long,
    isRemote: Boolean,
    e: CancellationException,
) {
    if (e is TimeoutCancellationException) {
        // 本轮真超时（30min 到点）：是错误，标记 Failed 并返回 Err（不再「Failed 状态 + Ok 结果」）。
        AppLog.w("prompt timeout: ${e.message}")
        // T3：超时是确定性终态，先记下来（首写优先，随后迟到的 marker 不会把它改成成功）。
        barriers.settle(sessionId, turnId, resolveTurnOutcome(completedNormally = true, error = e))
        val stillCurrentTurn = ledger.current(sessionId)?.turnId == turnId
        finalizeTurn(sessionId, turnId)
        if (stillCurrentTurn && sessionIds.isCurrent(gen)) setStatus(sessionId, SessionStatus.Failed)
        throw IllegalStateException("回复生成超时", e)
    }
    // 用户主动取消本轮：本轮已由 cancel() 结算（finalize partial + Idle + seal）。
    // 这里不做收尾、也不重抛 —— 正常返回 Ok，避免把新轮状态覆盖成 Failed。
    AppLog.w("prompt cancelled by user, turn=$turnId")
}

/**
 * T9 窄竞态兜底：prompt 已经返回、本轮 job 已经完成，但调用方的 await 还没恢复就被取消。
 * 那一刻 invokeOnCompletion 读到 detached=false 跳过收口，而调用方取消分支只打标记 ——
 * 若这里不补一刀，完整正文会永远不定稿（streaming 停在 1、会话卡 Running）。
 * 只在 job 确实已完成时结算，未完成的轮次仍交给 job 自己（真取消/超时语义不变）。
 */
internal suspend fun ChatRepository.recoverCompletedTurn(sessionId: String, turnId: String, pj: PromptJob?) {
    if (pj?.job?.isCompleted != true) return
    withContext(NonCancellable) { runCatchingDsh { finalizeTurn(sessionId, turnId) } }
}

/**
 * 发一条用户消息。
 *
 * [messageId] 是客户端生成的消息 id：同一条消息重复调用只会落一条（主键 REPLACE 去重），
 * 不再出现「同一条提问两个气泡」。
 */
internal suspend fun ChatRepository.sendMessageImpl(
    sessionId: String,
    text: String,
    images: List<String> = emptyList(),
    messageId: String? = null,
): DshResult<Unit> = runCatchingDsh {
    val gen = sessionIds.currentGeneration
    val endpointId = sessionIds.currentEndpointId
    // 发送前置条件（运行时/收尾屏障）失败时以前什么都不落：库里连 USER 行都没有、会话里没有气泡，
    // 用户只看到转圈几秒后一片空白。统一先落一条失败气泡，再原样抛给上层横幅。
    val t = try {
        requireTransport().also {
            // N-1 quarantine：仅 ACP 有旧 chunk 越屏障污染问题；本地模式无 turnId 合成 marker、无归属，不使用屏障。
            // 上一轮终态未被 collector 消费前绝不进 DB/seed。断连不自动放行，故不提示「切换连接」可解除。
            if (it is AcpTransport && barriers.isBlocked(sessionId)) error("上一轮回复仍在收尾，请稍后重试")
        }
    } catch (e: Throwable) {
        persistFailureBubble(sessionId, e)
        throw e
    }
    val isRemote = t is AcpTransport
    val now = System.currentTimeMillis()
    val attachmentsJson = org.json.JSONArray().apply { images.forEach { put(it) } }.toString()
    db.messageDao().upsert(
        MessageEntity(
            id = messageId ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = ChatMessage.Role.USER.name,
            text = text,
            at = now,
            attachmentsJson = attachmentsJson,
        ),
    )
    val turnId = UUID.randomUUID().toString()
    ledger.seed(sessionId, turnId)
    setStatus(sessionId, SessionStatus.Running)
    db.sessionDao().byId(sessionId)?.let {
        db.sessionDao().upsert(it.copy(updatedAt = now, preview = text.take(80), messageCount = it.messageCount + 1))
    }
    // T9：调用方是否已离开本轮（VM 销毁/作用域取消）。离开后本轮照跑，收口交给终态 marker。
    val detached = AtomicBoolean(false)
    // T9 竞态：pj 必须在 try 外可见 —— 调用方取消分支要用它判断「本轮是否其实已经跑完」。
    var pj: PromptJob? = null
    try {
        // M2（B2）：ACP 首次实际发送前真实补建远端会话；本地模式直接本地 id。
        val remoteId = if (isRemote) {
            ensureRemoteSession(sessionId, gen, endpointId) ?: error("远端会话补建失败或连接已切换")
        } else {
            sessionId
        }
        // 补建跨挂起：prompt 前再核代际，旧连接结果不污染当前端点。
        if (!sessionIds.isCurrent(gen)) error("连接已切换，放弃本轮")
        // T9：本轮执行所有权在仓库作用域（应用级），不挂调用方作用域。
        // 调用方（UI/VM）被取消时只停止 await —— 不 cancel 远端、不把半截正文定稿成完整回答。
        pj = startTurn(sessionId, turnId, remoteId, t, text, images, isRemote, detached)
        pj.job.await()
        // 真实结束：完整正文 + 真实 durationMs 一次落库（幂等：终态 marker 先到则 no-op）。
        withContext(NonCancellable) { finalizeTurn(sessionId, turnId) }
    } catch (e: CancellationException) {
        // P3/T9：先区分「调用方离开」与「本轮被打断（cancel() / 30min 超时）」。
        if (!currentCoroutineContext().isActive) {
            // T9：调用方离开 ≠ 本轮结束：只停止 await，不 cancel 远端、不定稿半截、不置 Idle。
            detached.set(true)
            // 但 job 已经跑完、只是 await 没来得及恢复的那一瞬，本轮其实已经真实结束：补收口。
            recoverCompletedTurn(sessionId, turnId, pj)
            throw e
        }
        settleInterruptedTurn(sessionId, turnId, gen, isRemote, e)
    } catch (e: Throwable) {
        failTurn(sessionId, turnId, gen, e)
    }
}

internal suspend fun ChatRepository.cancelImpl(sessionId: String): DshResult<Unit> {
    // 先捕获当前轮 id 再按它结算：打断期间即便新轮已启动，也绝不会误结算新轮。
    val turnId = ledger.current(sessionId)?.turnId
    // 已经流出来的东西不能跟着取消一起消失：用户按下停止时最常问的是
    // 「我刚才看到的那半句呢」。keepPartial 让半截正文（哪怕只有思维链）
    // 也落成一条气泡，并标注被中断。
    // 先本地结算（无论远端 cancel 成不成都到 Idle）：本轮已 seal，
    // 随后 sendMessage 的 await 抛出时 stillCurrentTurn==false，不会误写 Failed。
    // 本地结算用 runCatchingDsh 兜住 DB 落库异常，但结果必须保留：本地落库失败时
    // 即便远端 cancel 成功也不能返回 Ok（本地结算失败是硬错误，要对 8c 可见）。
    val localResult = runCatchingDsh {
        finalizeTurn(sessionId, expectTurnId = turnId, keepPartial = true)
        // 用户自己停的，不算故障。
        setStatus(sessionId, SessionStatus.Idle)
    }
    if (localResult is DshResult.Err) return localResult
    // P5：捕获本轮挂在 prompt 上的子任务（若有），但不再取消它 —— cancel 是发通知，
    // prompt 会等 server settle 后以「cancelled」响应 + 合成终态 marker 自然完成。
    val pj = promptJobs[sessionId]?.takeIf { it.turnId == turnId }
    // P4：远端取消结果如实上报（ACP 取消是通知无响应；其它传输失败/超时仍需透 Err）。
    val t = _transport.value
    if (t != null) {
        // M2：ACP 无映射 = 从没建过远端，没有可取消的远端轮；本地模式 remoteId==sessionId。
        val remoteId = remoteIdOrNull(sessionId, t is AcpTransport)
        if (remoteId != null) {
            try {
                t.cancel(remoteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.w("cancel remote failed: ${e.message}")
                return DshResult.Err(e.message ?: "取消远端失败", e)
            }
        }
    }
    // P5 barrier 有界等待：本轮 prompt 任务会在「响应到达 + 合成 marker 被 collector 消费」后完成。
    // 这里只观察、不取消；未确认则 Err + 保留 remote/history + sendGate 仍被旧轮持有挡新 prompt。
    if (pj != null) {
        try {
            withTimeout(15_000L) { pj.job.await() }
        } catch (e: TimeoutCancellationException) {
            AppLog.w("cancel barrier timeout: ${e.message}")
            // N-1/N-2：终态未确认 —— barrier 的 promptFinished 仍是 false（prompt 尚未结束），
            // 天然继续挡新 seed，无需额外标记。
            return DshResult.Err("取消未确认，远端可能仍在生成", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 断开/其它异常：prompt 已失败，本地已 Idle，如实上报 Err 不抛裸异常给 VM。
            AppLog.w("cancel barrier failed: ${e.message}")
            return DshResult.Err(e.message ?: "取消失败", e)
        }
    }
    return DshResult.Ok(Unit)
}

/**
 * 「插入」：不打断当前生成，把这句话交给运行时，在下一轮请求里生效。
 * 用户消息照常落库，所以气泡会立刻出现，只是不会另起一轮。
 */
internal suspend fun ChatRepository.interjectImpl(sessionId: String, text: String): DshResult<Unit> = runCatchingDsh {
    val now = System.currentTimeMillis()
    db.messageDao().upsert(
        MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = ChatMessage.Role.USER.name,
            text = text,
            at = now,
        ),
    )
    db.sessionDao().byId(sessionId)?.let {
        db.sessionDao().upsert(it.copy(updatedAt = now, preview = text.take(80), messageCount = it.messageCount + 1))
    }
    val t = requireTransport()
    // M2：ACP 无映射时没有远端会话可插，跳过远端调用（本地气泡照常落库）。
    remoteIdOrNull(sessionId, t is AcpTransport)?.let { t.interject(it, text) }
}
