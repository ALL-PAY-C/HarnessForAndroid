package dev.dsh.mobile.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 协议层不写死 UI 文案：本地引擎建会话 / 回退会话的标题留空，
 * 由 core/data 的 remote?.title?.ifBlank { title } 兜底成调用方按当前语言传入的标题
 * （否则 English 界面新建会话仍显示中文「新会话」）。
 */
class LocalSessionTitleTest {

    private fun transport() = LocalEngineTransport(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        configProvider = { null },
        historyProvider = { emptyList() },
        shellExec = { "" },
    )

    @Test
    fun `new session leaves title blank for caller localized title`() = runBlocking {
        assertEquals("", transport().newSession("/tmp").title)
    }

    @Test
    fun `unknown session fallback leaves title blank`() = runBlocking {
        assertEquals("", transport().resumeSession("missing").title)
    }
}
