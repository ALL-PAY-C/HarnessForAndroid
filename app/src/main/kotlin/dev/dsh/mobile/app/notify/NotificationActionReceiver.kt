package dev.dsh.mobile.app.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.dsh.mobile.app.DshApplication
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.core.common.AppScope
import dev.dsh.mobile.core.common.DshResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 通知栏里的「直接选择」入口。
 *
 * 两条来源、两条去路：
 *  · 工具权限卡（[ACTION_ANSWER] / [ACTION_TEXT]）→
 *    [dev.dsh.mobile.core.data.repo.ChatRepository.answerPermission]，
 *    带 requestId 与 owner，和会话里点确认走同一条逻辑（含 owner 校验与去重）。
 *  · 回复尾巴上的 A/B/C 选项（[ACTION_PICK]）→ 当成用户发言发回去：会话还在跑就
 *    排队，等这一轮结束自动发，不会把用户点的那一下吃掉。
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val container = (context.applicationContext as DshApplication).container
        val owner = intent.getStringExtra(EXTRA_OWNER)
        val requestId = intent.getStringExtra(EXTRA_REQUEST)

        val answer: String? = when (action) {
            ACTION_ANSWER -> intent.getStringExtra(EXTRA_OPTION)
            ACTION_TEXT -> RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(OngoingCenter.KEY_TEXT)?.toString()?.trim()?.ifBlank { null }
                ?: intent.getStringExtra(EXTRA_OPTION)
            ACTION_PICK -> intent.getStringExtra(EXTRA_OPTION)
            else -> return
        }
        if (answer.isNullOrBlank()) {
            AppLog.w("通知选项为空，忽略：$action")
            return
        }
        AppLog.i("通知内选择会话 $sessionId 请求 ${requestId ?: "-"} -> $answer")

        val pending = goAsync()
        AppScope.scope.launch {
            try {
                if (action == ACTION_PICK) {
                    val running = container.repo.turns.value[sessionId]?.running == true
                    val queued = running && runCatching {
                        val limit = container.settings.settings.first().queueLimit
                        container.sendQueue.enqueue(sessionId, answer, emptyList(), limit)
                    }.getOrElse { false }
                    if (!queued) {
                        val r = container.sendQueue.sendText(sessionId, answer)
                        if (r is DshResult.Err) AppLog.w("通知内发送失败：${r.message}", r.cause)
                    }
                } else {
                    when (val r = container.repo.answerPermission(sessionId, requestId.orEmpty(), answer, owner)) {
                        is DshResult.Err -> AppLog.w("通知内确认失败：${r.message}", r.cause)
                        is DshResult.Ok -> Unit
                    }
                }
            } catch (t: Throwable) {
                AppLog.w("通知内选择失败：${t.message}", t)
            } finally {
                // 顺手把那条常驻通知刷一下，避免点了还停在「等你确认」。
                container.ongoingCenter.refresh()
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "dev.dsh.mobile.notify.ANSWER"
        const val ACTION_TEXT = "dev.dsh.mobile.notify.ANSWER_TEXT"
        /** 回复尾巴上的 A/B/C：点了就是发一条用户消息。 */
        const val ACTION_PICK = "dev.dsh.mobile.notify.PICK"
        const val EXTRA_SESSION = "dsh.extra.session"
        const val EXTRA_REQUEST = "dsh.extra.request"
        const val EXTRA_OPTION = "dsh.extra.option"
        const val EXTRA_OWNER = "dsh.extra.owner"
    }
}
