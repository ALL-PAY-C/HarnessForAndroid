package dev.dsh.mobile.feature.settings

import dev.dsh.mobile.design.R as DesignR
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.data.mcp.MCP_TRANSPORTS
import dev.dsh.mobile.core.data.mcp.McpKeyValue
import dev.dsh.mobile.core.data.mcp.McpServerRecord
import dev.dsh.mobile.core.model.port.McpServerStoreApi
import dev.dsh.mobile.core.data.mcp.newMcpServerId
import dev.dsh.mobile.core.data.mcp.probeMcpServer
import dev.dsh.mobile.core.data.mcp.sanitizeServerName
import dev.dsh.mobile.core.data.mcp.serverNameError
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqRow
import dev.dsh.mobile.design.QqScreenTopBar
import dev.dsh.mobile.design.QqSpace
import dev.dsh.mobile.design.SectionSpacing
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.design.qqPageBottomInset
import kotlinx.coroutines.launch

/** 一次测试连接的结果：成功与否决定颜色，文案已本地化，不再靠前缀猜。 */
private data class ProbeMsg(val ok: Boolean, val text: String)

/**
 * 设置 → MCP 服务器（T9.10 P1）。
 *
 * 只做应用内直连的 HTTP/SSE 服务器：列表 + 增删改 + 启停 + 测试连接。
 * 测试连接走真实网络，失败也只是把错误文案画在行下面，不弹窗、不崩。
 */
@Composable
fun McpServersScreen(store: McpServerStoreApi, onBack: () -> Unit) {
    val p = LocalQq.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<McpServerRecord?>(null) }
    var probes by remember { mutableStateOf<Map<String, ProbeMsg>>(emptyMap()) }
    var probingId by remember { mutableStateOf<String?>(null) }

    fun runProbe(record: McpServerRecord) {
        if (probingId != null) return
        probingId = record.id
        probes = probes - record.id
        scope.launch {
            val result = probeMcpServer(record)
            probes = probes + (record.id to ProbeMsg(
                ok = result.ok,
                text = if (result.ok) {
                    ctx.getString(DesignR.string.mcp_probe_ok, result.server, result.tools.size)
                } else {
                    result.error ?: ctx.getString(DesignR.string.mcp_probe_failed)
                },
            ))
            probingId = null
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize()) {
            QqScreenTopBar(title = stringResource(DesignR.string.mcp_title), isBack = true, onNavigationClick = onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        PaddingValues(
                            start = SectionSpacing.cardGap,
                            top = SectionSpacing.cardGap,
                            end = SectionSpacing.cardGap,
                            bottom = SectionSpacing.cardGap + qqPageBottomInset(tabBarVisible = false),
                        ),
                    ),
                verticalArrangement = Arrangement.spacedBy(SectionSpacing.cardGap),
            ) {
                // T9.10-P4：两条链路的执行位置不同，权限语义也不同，先说清楚再让用户配。
                Text(
                    stringResource(DesignR.string.mcp_hint),
                    color = p.t3,
                    fontSize = qqFontBody(11.5.sp),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                SectionFrame(title = stringResource(DesignR.string.mcp_section_configured), icon = Icons.Filled.Widgets, tint = p.accent) {
                    if (servers.isEmpty()) {
                        Text(
                            stringResource(DesignR.string.mcp_empty),
                            color = p.t3,
                            fontSize = qqFontBody(12.sp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                        QqCardDivider()
                    }
                    servers.forEach { server ->
                        Column {
                            QqRow(
                                icon = Icons.Filled.Widgets,
                                title = server.name,
                                subtitle = server.transport + " · " + server.url,
                                tint = if (server.enabled) p.accent else p.t3,
                                onClick = { editing = server },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(
                                            onClick = { runProbe(server) },
                                            enabled = probingId == null,
                                        ) {
                                            Text(
                                                if (probingId == server.id) stringResource(DesignR.string.mcp_testing) else stringResource(DesignR.string.mcp_test),
                                                fontSize = qqFontBody(12.sp),
                                            )
                                        }
                                        Switch(
                                            checked = server.enabled,
                                            onCheckedChange = { on -> servers = store.setEnabled(server.id, on) },
                                        )
                                    }
                                },
                            )
                            probes[server.id]?.let { msg ->
                                Text(
                                    msg.text,
                                    color = if (msg.ok) p.green else p.red,
                                    fontSize = qqFontBody(11.5.sp),
                                    modifier = Modifier.padding(start = 56.dp, end = 14.dp, bottom = 8.dp),
                                )
                            }
                            QqCardDivider()
                        }
                    }
                    QqRow(
                        icon = Icons.Filled.Add,
                        title = stringResource(DesignR.string.mcp_add_title),
                        subtitle = stringResource(DesignR.string.mcp_add_sub),
                        tint = p.accent,
                        onClick = {
                            editing = McpServerRecord(
                                id = newMcpServerId(),
                                name = "",
                                transport = "http",
                                url = "",
                            )
                        },
                    )
                }

                editing?.let { draft ->
                    McpEditor(
                        draft = draft,
                        others = servers.filterNot { it.id == draft.id },
                        onCancel = { editing = null },
                        onSave = { saved ->
                            servers = store.upsert(saved)
                            editing = null
                        },
                        onDelete = {
                            servers = store.delete(draft.id)
                            editing = null
                        },
                        onProbe = { runProbe(it) },
                        probing = probingId == draft.id,
                        probeMessage = probes[draft.id],
                    )
                }
            }
        }
    }
}

@Composable
private fun McpEditor(
    draft: McpServerRecord,
    others: List<McpServerRecord>,
    onCancel: () -> Unit,
    onSave: (McpServerRecord) -> Unit,
    onDelete: () -> Unit,
    onProbe: (McpServerRecord) -> Unit,
    probing: Boolean,
    probeMessage: ProbeMsg?,
) {
    val p = LocalQq.current
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var url by remember(draft.id) { mutableStateOf(draft.url) }
    var transport by remember(draft.id) { mutableStateOf(draft.transport) }
    var headersText by remember(draft.id) {
        mutableStateOf(draft.headers.joinToString("\n") { it.name + ": " + it.value })
    }
    var timeoutText by remember(draft.id) { mutableStateOf((draft.toolCallTimeoutMs / 1000L).toString()) }

    // 新建草稿一打开两个字段都是空的：错误提示等首次失焦或点过保存/测试之后再显示，
    // 否则一进来就是两行红字。valid 仍按真实校验算，保存按钮的门槛不变。
    var touched by remember(draft.id) { mutableStateOf(false) }
    val rawNameError = serverNameError(name, others)
    val rawUrlError = if (url.isBlank()) stringResource(DesignR.string.mcp_err_url_empty) else if (!url.startsWith("http")) stringResource(DesignR.string.mcp_err_url_scheme) else null
    val headers = parseHeaders(headersText)
    val timeoutMs = (timeoutText.toLongOrNull() ?: 0L) * 1000L
    val rawTimeoutError = if (timeoutMs !in 1_000L..600_000L) stringResource(DesignR.string.mcp_err_timeout) else null
    val valid = rawNameError == null && rawUrlError == null && rawTimeoutError == null
    val nameError = if (touched) rawNameError else null
    val urlError = if (touched) rawUrlError else null
    val timeoutError = if (touched) rawTimeoutError else null

    SectionFrame(title = stringResource(DesignR.string.common_edit), icon = Icons.Filled.Widgets, tint = p.accent2) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(QqSpace.s()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(DesignR.string.mcp_field_name)) },
                placeholder = { Text("files") },
                isError = nameError != null,
                modifier = Modifier.fillMaxWidth().then(blurred { touched = true }),
            )
            if (nameError != null) {
                Text(nameError, color = p.red, fontSize = qqFontBody(11.5.sp))
            } else if (name.isNotBlank()) {
                Text(stringResource(DesignR.string.mcp_actual_prefix, sanitizeServerName(name)), color = p.t3, fontSize = qqFontBody(11.5.sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(QqSpace.s())) {
                MCP_TRANSPORTS.forEach { kind ->
                    FilterChip(
                        selected = transport == kind,
                        onClick = { transport = kind },
                        label = { Text(kind.uppercase(), fontSize = qqFontBody(12.sp)) },
                    )
                }
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text(stringResource(DesignR.string.mcp_field_url)) },
                placeholder = { Text("https://example.com/mcp") },
                isError = urlError != null,
                modifier = Modifier.fillMaxWidth().then(blurred { touched = true }),
            )
            if (urlError != null) {
                Text(urlError, color = p.red, fontSize = qqFontBody(11.5.sp))
            }
            OutlinedTextField(
                value = headersText,
                onValueChange = { headersText = it },
                label = { Text(stringResource(DesignR.string.mcp_field_headers)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { timeoutText = it },
                singleLine = true,
                label = { Text(stringResource(DesignR.string.mcp_field_timeout)) },
                isError = timeoutError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (timeoutError != null) {
                Text(timeoutError, color = p.red, fontSize = qqFontBody(11.5.sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(QqSpace.s())) {
                // 保存/测试始终可点：点一下就把校验结果摆出来，比灰按钮更说明问题。
                val snapshot = {
                    draft.copy(
                        name = sanitizeServerName(name),
                        url = url.trim(),
                        transport = transport,
                        headers = headers,
                        toolCallTimeoutMs = timeoutMs,
                    )
                }
                TextButton(
                    onClick = {
                        touched = true
                        if (valid) onSave(snapshot())
                    },
                ) { Text(stringResource(DesignR.string.common_save)) }
                TextButton(
                    onClick = {
                        touched = true
                        if (valid) onProbe(snapshot())
                    },
                ) { Text(if (probing) stringResource(DesignR.string.mcp_testing_ellipsis) else stringResource(DesignR.string.mcp_test_connect)) }
                TextButton(onClick = onCancel) { Text(stringResource(DesignR.string.common_cancel), color = p.t3) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(stringResource(DesignR.string.common_delete), color = p.red) }
            }
            probeMessage?.let { msg ->
                Text(
                    msg.text,
                    color = if (msg.ok) p.green else p.red,
                    fontSize = qqFontBody(11.5.sp),
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

/**
 * 首次失焦才触发 [onBlur]：Compose 在挂载时也会发一次 isFocused=false，
 * 直接当真会让新建草稿立刻变成「已触碰」。
 */
@Composable
private fun blurred(onBlur: () -> Unit): Modifier {
    var hadFocus by remember { mutableStateOf(false) }
    return Modifier.onFocusChanged { state ->
        if (state.isFocused) hadFocus = true else if (hadFocus) onBlur()
    }
}

/** 每行 Name: Value；没有冒号的行整行当名字（值为空），保证手滑也不丢输入。 */
private fun parseHeaders(text: String): List<McpKeyValue> = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        val index = line.indexOf(':')
        if (index <= 0) {
            McpKeyValue(line, "")
        } else {
            McpKeyValue(line.substring(0, index).trim(), line.substring(index + 1).trim())
        }
    }
    .toList()
