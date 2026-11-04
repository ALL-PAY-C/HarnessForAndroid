package dev.dsh.mobile.app.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.design.qqFontBody

/**
 * 安全模式顶部黄条。
 *
 * 用固定色而不是主题色：安全模式必须在任何外观（含液态材质、深色）下都显眼，
 * 跟着主题走会出现「深色主题下黄条变暗灰」这种自相矛盾的效果。
 */
@Composable
fun SafeModeBanner(reason: String, onOpenDiagnostics: () -> Unit) {
    val bg = Color(0xFFFFC53D)
    val fg = Color(0xFF3A2A00)
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            // 黄条画在 NavHost 之上，是整窗第一行：底色铺到状态栏下面，内容让开状态栏，
            // 否则时钟和信号图标会压在「安全模式」字样上。
            .statusBarsPadding()
            .clickable(onClick = onOpenDiagnostics)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "安全模式",
            color = fg,
            fontSize = qqFontBody(13.sp),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = reason.ifBlank { "上次启动连续失败" },
            color = fg.copy(alpha = 0.75f),
            fontSize = qqFontBody(12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "查看",
            color = fg,
            fontSize = qqFontBody(12.5.sp),
            fontWeight = FontWeight.Medium,
        )
    }
}
