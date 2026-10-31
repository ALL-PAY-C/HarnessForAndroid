package dev.dsh.mobile.core.data.repo

import android.content.Context
import android.content.SharedPreferences
import dev.dsh.mobile.core.protocol.ContextSummaryStore

/**
 * 上下文摘要的落地实现：SharedPreferences（"dsh_context_summary"），会话 id 作 key 后缀。
 *
 * 选它而不是新建 Room 表：一段自由文本 + 一次清理，加表要迁移、要级联删；这里读写都是内存缓存，
 * 一次压缩才写一次，频率低到可以忽略。
 * ponytail: 全部会话共用一个 prefs 文件；会话量是几十级，不做按会话分文件。
 */
class SharedPrefsSummaryStore(private val prefs: SharedPreferences?) : ContextSummaryStore {

    override fun read(sessionId: String): String =
        prefs?.getString(key(sessionId), null).orEmpty()

    override fun write(sessionId: String, text: String) {
        if (text.isBlank()) {
            clear(sessionId)
            return
        }
        prefs?.edit()?.putString(key(sessionId), text)?.apply()
    }

    override fun clear(sessionId: String) {
        prefs?.edit()?.remove(key(sessionId))?.apply()
    }

    private fun key(sessionId: String): String = "summary_" + sessionId

    companion object {
        const val FILE = "dsh_context_summary"

        /** 宿主拿不到 Context 时返回 [None] 语义的实例（读写都是空操作）。 */
        fun from(context: Context?): SharedPrefsSummaryStore =
            SharedPrefsSummaryStore(context?.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        val None = SharedPrefsSummaryStore(null)
    }
}
