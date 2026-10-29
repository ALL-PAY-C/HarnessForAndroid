package dev.dsh.mobile.feature.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.core.data.backup.BackupManager
import dev.dsh.mobile.core.data.backup.BackupScheduler
import dev.dsh.mobile.design.LocalQq

/**
 * Backup schedule editor: one or more times of day, default 05:00.
 *
 * Times are stored as "HH:mm" strings; the alarm only needs the next one, and a
 * missed slot is caught up at launch — so editing here never risks losing a
 * backup, it only changes when the next one happens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupScheduleCard(backup: BackupManager?, onChanged: () -> Unit = {}) {
    if (backup == null) return
    val context = LocalContext.current
    var times by remember { mutableStateOf(backup.times()) }
    var auto by remember { mutableStateOf(backup.autoEnabled()) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("05:00") }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = LocalQq.current.card),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("定时自动备份", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "默认 05:00，可以加多个时间点；没运行就下次启动补做",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = auto,
                    onCheckedChange = { enabled ->
                        auto = enabled
                        backup.setAutoEnabled(enabled)
                        if (enabled) {
                            BackupScheduler.schedule(context, backup)
                        } else {
                            BackupScheduler.cancel(context)
                        }
                        onChanged()
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                times.forEach { time ->
                    AssistChip(
                        onClick = {
                            if (times.size <= 1) return@AssistChip
                            val next = times - time
                            times = next
                            backup.setTimes(next)
                            BackupScheduler.schedule(context, backup)
                            onChanged()
                        },
                        label = { Text(time) },
                    )
                }
                AssistChip(
                    onClick = { adding = true },
                    label = { Text("+ 添加时间") },
                )
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加备份时间") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("HH:mm") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalised = normaliseTime(draft)
                    if (normalised != null && normalised !in times) {
                        val next = (times + normalised).sorted()
                        times = next
                        backup.setTimes(next)
                        BackupScheduler.schedule(context, backup)
                        onChanged()
                    }
                    adding = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text("取消") }
            },
        )
    }
}

/** "5:0" / "05:00" / "0500" → "05:00"; anything else is rejected. */
private fun normaliseTime(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    val (h, m) = when {
        raw.contains(":") -> raw.split(":").let {
            (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.toIntOrNull() ?: return null)
        }
        digits.length == 3 -> digits.take(1).toInt() to digits.drop(1).toInt()
        digits.length == 4 -> digits.take(2).toInt() to digits.drop(2).toInt()
        else -> return null
    }
    if (h !in 0..23 || m !in 0..59) return null
    return "%02d:%02d".format(h, m)
}
