package dev.dsh.mobile.app.update

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.core.data.update.UpdateInfo
import dev.dsh.mobile.core.data.update.Updater
import dev.dsh.mobile.core.data.update.readPromptedVersion
import dev.dsh.mobile.core.data.update.shouldPromptUpdateOnce
import dev.dsh.mobile.core.data.update.writePromptedVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AutoUpdate"

/** 更新说明最多展示这么多字，清单写太长也不至于把对话框撑成一整屏。 */
private const val MAX_NOTES_CHARS = 200

/** 对话框的三个态：问 / 下载中 / 下载失败（失败仍可退出）。 */
private sealed interface PromptState {
    val info: UpdateInfo

    data class Ask(override val info: UpdateInfo) : PromptState
    data class Downloading(override val info: UpdateInfo, val percent: Int) : PromptState
    data class Failed(override val info: UpdateInfo, val message: String) : PromptState
}

/**
 * 启动时的静默更新检查 + 「发现新版本」对话框。
 *
 * 判定全在 [shouldPromptUpdateOnce] 里（纯函数，有单测）；这里只管三件事：
 * 后台拉清单、把结果渲染成一个对话框、把用户的决定落盘。
 * 网络失败/超时完全静默——只 Log.w 一条，首屏照常。
 */
@Composable
fun AutoUpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PromptState?>(null) }

    LaunchedEffect(Unit) {
        val app = context.applicationContext
        val updater = Updater(app)
        val (installedCode, _) = updater.installedVersion()
        // check() 自己就在 Dispatchers.IO 里；失败只记日志，绝不弹错误框。
        val info = withContext(Dispatchers.IO) { updater.check() }.getOrElse { err ->
            Log.w(TAG, "更新检查失败（静默跳过）：${err.message}")
            return@LaunchedEffect
        }
        val prompted = readPromptedVersion(app)
        if (shouldPromptUpdateOnce(info, installedCode, prompted)) {
            val target = info ?: return@LaunchedEffect
            state = PromptState.Ask(target)
        }
    }

    val current = state ?: return
    // 「稍后」与点外面关掉是同一件事：记下这个版本号，本次及以后都不再为它打扰。
    val dismiss = {
        writePromptedVersion(context.applicationContext, current.info.versionCode)
        state = null
    }
    val busy = current is PromptState.Downloading

    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("发现新版本 ${current.info.versionName}") },
        text = {
            Column {
                val (installedCode, installedName) = remember {
                    Updater(context.applicationContext).installedVersion()
                }
                Text(
                    "当前版本：${installedName.ifBlank { installedCode.toString() }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val notes = current.info.notes.trim()
                if (notes.isNotEmpty()) {
                    Text(
                        notes.take(MAX_NOTES_CHARS) + if (notes.length > MAX_NOTES_CHARS) "…" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                when (current) {
                    is PromptState.Downloading -> Text(
                        "正在下载… ${current.percent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    is PromptState.Failed -> Text(
                        "下载失败：${current.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    is PromptState.Ask -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val info = current.info
                    val app = context.applicationContext
                    state = PromptState.Downloading(info, 0)
                    scope.launch {
                        val updater = Updater(app)
                        // onProgress 回调在 IO 线程：切回主线程再写状态。
                        val result = updater.download(info) { pct ->
                            scope.launch { state = PromptState.Downloading(info, pct) }
                        }
                        result.onSuccess { file ->
                            state = null
                            runCatching { app.startActivity(updater.installIntent(file)) }
                                .onFailure { e -> Log.w(TAG, "拉起安装器失败：${e.message}") }
                        }.onFailure { e ->
                            state = PromptState.Failed(info, e.message ?: "未知错误")
                        }
                    }
                },
            ) {
                Text(if (current is PromptState.Downloading) "下载中 ${current.percent}%" else "立即更新")
            }
        },
        dismissButton = {
            TextButton(onClick = { dismiss() }) { Text("稍后") }
        },
    )
}
