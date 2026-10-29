package dev.dsh.mobile.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * T1 基线不变量（RED 前置）：
 * 目标行为 = 「退出会话只解除前台订阅，不取消共享 transport 上的这一轮任务」。
 * 当前实现与该不变量冲突，因此本文件的 1、3 号断言在 T1 基线上是 RED；
 * T9 修好之后必须自动转 GREEN，不需要改动本文件。
 *
 * 证据来源（设备三连跑，见 .mock/agent-env/baseline/REPORT.md）：
 * - 停留 31s：60/60 chunk 落库，durationMs=31714
 * - 1.27s 后返回：5/60 chunk 落库，durationMs=2946
 * - 18.2s 后返回：38/60 chunk 落库，durationMs=20263
 * 三次服务端都完整写出 60/60 且无 client abort => 断的不是 transport，是 UI 作用域。
 */
class T1ExitScopeTest {

    private val projectRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        requireNotNull(dir) { "找不到项目根（settings.gradle.kts）" }
    }

    private fun source(relative: String): String {
        val f = File(projectRoot, relative)
        require(f.exists()) { "源文件不存在: ${f.path}" }
        return f.readText()
    }

    private val chatViewModel =
        "feature/chat/src/main/kotlin/dev/dsh/mobile/feature/chat/ChatViewModel.kt"
    private val chatRepository =
        "core/data/src/main/kotlin/dev/dsh/mobile/core/data/repo/ChatRepository.kt"
    private val appNav =
        "app/src/main/kotlin/dev/dsh/mobile/app/nav/AppNav.kt"

    /** 1) 发送链路不得挂在 viewModelScope：NavBackStackEntry 弹栈即取消，任务随页面一起死。 */
    @Test
    fun sendMustNotRunOnViewModelScope() {
        val text = source(chatViewModel)
        val start = text.indexOf("fun send(text: String")
        require(start > 0) { "ChatViewModel.send 签名变了，先核对基线" }
        val end = text.indexOf("private suspend fun deliver(", start)
        require(end > start) { "ChatViewModel.deliver 签名变了，先核对基线" }
        val body = text.substring(start, end)
        assertFalse(
            "退出会话不得取消本轮：send() 仍在 viewModelScope 内启动流式发送 " +
                "（feature/chat/src/main/kotlin/dev/dsh/mobile/feature/chat/ChatViewModel.kt:313），" +
                "页面弹栈会 cancel 掉正在跑的 prompt",
            body.contains("viewModelScope.launch"),
        )
    }

    /** 2) 导航离开聊天路由不得断开共享连接（当前已满足，作为防回归锁）。 */
    @Test
    fun chatExitMustNotDisconnectTransport() {
        val text = source(appNav)
        assertFalse(
            "离开聊天路由不得断开共享 transport（app/src/main/kotlin/dev/dsh/mobile/app/nav/AppNav.kt）",
            text.contains("container.disconnect(") || text.contains("appContainer.disconnect("),
        )
    }

    /** 3) 「真调用方取消」分支不得把半截正文定稿成完整回答 + Idle。 */
    @Test
    fun callerCancellationMustNotFinalizeTurnAsComplete() {
        val text = source(chatRepository)
        val marker = "if (!currentCoroutineContext().isActive)"
        val at = text.indexOf(marker)
        require(at > 0) { "ChatRepository 的取消分支变了，先核对基线" }
        val block = text.substring(at, minOf(text.length, at + 800))
        assertFalse(
            "调用方（UI 作用域）被取消时不得 finalizeTurn(keepPartial=true)+Idle 把半截回答定稿：" +
                "core/data/src/main/kotlin/dev/dsh/mobile/core/data/repo/ChatRepository.kt:495-503",
            block.contains("finalizeTurn("),
        )
    }
}
