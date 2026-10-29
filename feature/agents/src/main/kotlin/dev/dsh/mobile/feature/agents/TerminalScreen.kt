package dev.dsh.mobile.feature.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.dsh.mobile.core.common.AppLog
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.toolbridge.ShellBridge
import dev.dsh.mobile.toolbridge.ShellSpec
import kotlinx.coroutines.launch

/**
 * 内置终端。两种模式：
 *
 * - **PTY**：termux 的 terminal-emulator + libtermux.so 真开伪终端，
 *   vim / top / logcat -f 这类交互式程序都能跑。shell 优先内置 busybox，缺 ABI 时退 /system/bin/sh。
 * - **简易**：不开 PTY，一条一条丢给 [ShellBridge.exec]，只贴输出。
 *   某些 ABI 打不进 libtermux.so（或设备拒绝 fork）时仍然可用。
 *
 * 两个模式共用一套「标签页 + 字号 + 快捷键条」的交互，不做两副面孔。
 */
@Composable
fun TerminalScreen(shell: ShellBridge, onBack: () -> Unit) {
    val p = LocalQq.current
    val ctx = LocalContext.current
    var spec by remember { mutableStateOf<ShellSpec?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pty by remember { mutableStateOf(true) }
    var textSize by remember { mutableIntStateOf(13) }

    LaunchedEffect(Unit) {
        runCatching { shell.terminalSpec() }
            .onSuccess { spec = it }
            .onFailure { failure = it.message ?: "终端初始化失败"; pty = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // 第一行：返回 + 模式胶囊 + 字号。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
            Spacer(Modifier.width(8.dp))
            ModePill(
                label = (spec?.label ?: "shell") + " · " + if (pty) "PTY" else "简易",
                onClick = { pty = !pty },
            )
            Spacer(Modifier.weight(1f))
            if (pty) {
                TextChip("A-") { textSize = (textSize - 1).coerceIn(9, 22) }
                Spacer(Modifier.width(4.dp))
                TextChip("A+") { textSize = (textSize + 1).coerceIn(9, 22) }
            }
        }

        when {
            failure != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(failure!!, color = p.t2, fontSize = 13.sp)
            }

            spec == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("正在启动 shell…", color = p.t3, fontSize = 13.sp)
            }

            pty -> PtyArea(spec!!, textSize)
            else -> SimpleArea(shell, spec!!)
        }
    }
}

/** 顶部模式胶囊，形态跟参考的那种「Ubuntu · PTY」一致：点了就切模式。 */
@Composable
private fun ModePill(label: String, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(p.hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.t1, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TextChip(label: String, onClick: () -> Unit) {
    val p = LocalQq.current
    Text(
        label,
        color = p.t2,
        fontSize = 12.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

// ---------------------------------------------------------------- PTY 模式

private class PtyTab(val title: String, val session: TerminalSession)

@Composable
private fun PtyArea(spec: ShellSpec, textSize: Int) {
    val p = LocalQq.current
    val ctx = LocalContext.current
    val clipboard = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val viewRef = remember { mutableStateOf<TerminalView?>(null) }
    var exited by remember { mutableStateOf(Int.MIN_VALUE) }
    var active by remember { mutableIntStateOf(0) }
    var serial by remember { mutableIntStateOf(1) }
    val tabs = remember { mutableStateListOf<PtyTab>() }

    val sessionClient = remember {
        terminalSessionClient(
            view = viewRef,
            clipboard = clipboard,
            exited = { v -> exited = v },
        )
    }

    fun newTab(): PtyTab {
        val s = TerminalSession(
            spec.shell,
            spec.cwd,
            spec.args.toTypedArray(),
            spec.env.toTypedArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            sessionClient,
        )
        val tab = PtyTab("终端 " + serial, s)
        serial += 1
        return tab
    }

    LaunchedEffect(spec) {
        if (tabs.isEmpty()) tabs += newTab()
    }

    DisposableEffect(Unit) {
        onDispose { tabs.forEach { runCatching { it.session.finishIfRunning() } } }
    }

    LaunchedEffect(textSize) { viewRef.value?.setTextSize(textSize) }
    LaunchedEffect(active) { exited = Int.MIN_VALUE }

    val viewClient = remember(spec) { terminalViewClient(view = viewRef) }
    val current = tabs.getOrNull(active)

    Column(Modifier.fillMaxSize()) {
        // 标签行：终端 1 × … + 新建
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { i, tab ->
                TabChip(
                    title = tab.title,
                    selected = i == active,
                    onSelect = { active = i },
                    onClose = if (tabs.size > 1) {
                        {
                            runCatching { tab.session.finishIfRunning() }
                            tabs.removeAt(i)
                            active = (active.coerceAtMost(tabs.size - 1))
                        }
                    } else null,
                )
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        tabs += newTab()
                        active = tabs.lastIndex
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, tint = p.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text("新建", color = p.accent, fontSize = 12.5.sp)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0B0F14)),
        ) {
            if (current == null) {
                Text("正在启动 shell…", color = p.t3, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        TerminalView(c, null).apply {
                            setTerminalViewClient(viewClient)
                            // 顺序不能反：updateSize 里对 mRenderer 是非空断言
                            setTextSize(textSize)
                            attachSession(current.session)
                            // 上游 activity_termux.xml 里这两个属性是终端能收键盘的前提：
                            // 没有 focusableInTouchMode，requestFocus() 在触摸模式下直接失败，
                            // 视图拿不到焦点 → IME 不认这个 view（logcat: "is not served"）→ 字符进不了 PTY。
                            isFocusable = true
                            isFocusableInTouchMode = true
                            requestFocus()
                            viewRef.value = this
                        }
                    },
                    update = { v ->
                        if (v.currentSession !== current.session) {
                            v.setTerminalViewClient(viewClient)
                            v.attachSession(current.session)
                            v.requestFocus()
                        }
                    },
                )
            }
        }

        if (exited != Int.MIN_VALUE) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(p.card)
                    .clickable { tabs += newTab().also { active = tabs.lastIndex } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("shell 已退出（" + exited + "）· 点这里新开一个", color = p.amber, fontSize = 12.sp)
            }
        }

        KeyBar(
            onKey = { bytes -> current?.session?.let { runCatching { it.write(bytes, 0, bytes.size) } } },
            onScroll = { rows ->
                val v = viewRef.value ?: return@KeyBar
                val emu = v.mEmulator ?: return@KeyBar
                val history = emu.getScreen().activeTranscriptRows
                v.mTopRow = (v.mTopRow - rows).coerceIn(-history, 0)
                v.invalidate()
            },
        )
    }
}

@Composable
private fun TabChip(title: String, selected: Boolean, onSelect: () -> Unit, onClose: (() -> Unit)?) {
    val p = LocalQq.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) p.accent.copy(alpha = 0.16f) else p.hover)
            .clickable(onClick = onSelect)
            .padding(start = 13.dp, end = if (onClose == null) 13.dp else 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (selected) p.accent else p.t2,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        if (onClose != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = if (selected) p.accent else p.t3,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** Esc/Tab/Ctrl/Alt/方向键 + 翻页。软键盘负责普通字符。 */
@Composable
private fun KeyBar(onKey: (ByteArray) -> Unit, onScroll: (Int) -> Unit) {
    val p = LocalQq.current
    fun send(text: String) = onKey(text.toByteArray(Charsets.UTF_8))

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyChip("ESC") { send("") }
        KeyChip("TAB") { send("	") }
        KeyChip("CTRL") { onKey(byteArrayOf(3)) }
        KeyChip("ALT") { onKey(byteArrayOf(27)) }
        KeyChip("\u2191") { onKey(byteArrayOf(16)) }
        KeyChip("\u2193") { onKey(byteArrayOf(14)) }
        KeyChip("\u2190") { send("[D") }
        KeyChip("\u2192") { send("[C") }
        KeyChip("^D") { onKey(byteArrayOf(4)) }
        KeyChip("^Z") { onKey(byteArrayOf(26)) }
        KeyChip("^L") { onKey(byteArrayOf(12)) }
        KeyChip("PgUp") { onScroll(10) }
        KeyChip("PgDn") { onScroll(-10) }
        KeyChip("清屏", accent = true) { send("clear\n") }
    }
}

@Composable
private fun KeyChip(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val p = LocalQq.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (accent) p.accent.copy(alpha = 0.14f) else p.hover)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (accent) p.accent else p.t1,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------- 简易模式

private data class SimpleLine(val text: String, val command: Boolean)

@Composable
private fun SimpleArea(shell: ShellBridge, spec: ShellSpec) {
    val p = LocalQq.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<SimpleLine>() }
    var cmd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(lines.size) { runCatching { scroll.scrollTo(scroll.maxValue) } }

    fun run() {
        val c = cmd.trim()
        if (c.isEmpty() || busy) return
        cmd = ""
        lines += SimpleLine("$ " + c, true)
        busy = true
        scope.launch {
            val r = runCatching { shell.exec(c, timeoutMs = 30_000) }
            val out = r.getOrNull()?.asToolOutput(limit = 60_000)?.trimEnd().orEmpty()
            lines += SimpleLine(out.ifBlank { "(无输出)" } + (r.getOrNull()?.let { if (it.code != 0) "\n(退出码 " + it.code + ")" else "" } ?: ""), false)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.card)
                .verticalScroll(scroll)
                .padding(12.dp),
        ) {
            Text(
                spec.label + " · " + spec.shell,
                color = p.t3,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            lines.forEach { line ->
                Text(
                    line.text,
                    color = if (line.command) p.accent else p.t1,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (busy) {
                Text("执行中…", color = p.t3, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(p.hover)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", color = p.accent, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = cmd,
                onValueChange = { cmd = it },
                singleLine = true,
                textStyle = TextStyle(color = p.t1, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { run() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (cmd.isEmpty()) Text("输入命令，回车执行", color = p.t3, fontSize = 13.sp)
                    inner()
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (busy) "…" else "执行",
                color = p.accent,
                fontSize = 12.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { run() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 回调实现

private fun terminalSessionClient(
    view: androidx.compose.runtime.MutableState<TerminalView?>,
    clipboard: ClipboardManager,
    exited: (Int) -> Unit,
): TerminalSessionClient = object : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        view.value?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        exited(finishedSession.mShellExitStatus)
        view.value?.onScreenUpdated()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text)) }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
        if (text.isNullOrEmpty() || session == null) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {
        view.value?.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        AppLog.i("terminal: shell pid=" + pid)
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {
        AppLog.e("terminal: " + tag + ": " + message)
    }

    override fun logWarn(tag: String?, message: String?) {
        AppLog.w("terminal: " + tag + ": " + message)
    }

    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        AppLog.e("terminal: " + tag + ": " + message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        AppLog.e("terminal: " + tag, e)
    }
}

private fun terminalViewClient(
    view: androidx.compose.runtime.MutableState<TerminalView?>,
): TerminalViewClient = object : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent) {
        view.value?.requestFocus()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) {
        AppLog.e("terminal-view: " + tag + ": " + message)
    }

    override fun logWarn(tag: String?, message: String?) {
        AppLog.w("terminal-view: " + tag + ": " + message)
    }

    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        AppLog.e("terminal-view: " + tag + ": " + message, e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        AppLog.e("terminal-view: " + tag, e)
    }
}
