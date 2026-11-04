// 卡片容器：设置卡片、分组框架与章节标题/折叠箭头。
package dev.dsh.mobile.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn

/** Rounded card used for grouped lists and settings blocks. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalQq.current
    val style = LocalSurfaceStyle.current
    val border = qqBorder()
    val shape = QqShape.card()
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, shape, clip = false)
            .clip(shape)
            .background(p.card)
            // 材质叠层（液态高光 / 亚克力细噪点）画在底色之上、内容之下；无材质时是空操作。
            .panelSheen()
            // 外观设置里的描边：0dp 时这根 Modifier 根本不进链，旧外观一个像素都不变。
            // 液态材质用渐变描边模拟边缘折射感，其余材质仍是纯色分割线。
            .then(
                when {
                    border <= 0.dp -> Modifier
                    style.edge != null -> Modifier.border(border, style.edge, shape)
                    else -> Modifier.border(border, p.line, shape)
                },
            ),
    ) { content() }
}

/** Settings section copy uses one typography and spacing scale at both nesting levels. */
object SectionSpacing {
    val cardGap = 12.dp
    val horizontal = 14.dp
    val vertical = 12.dp
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val p = LocalQq.current
    Column(modifier.padding(vertical = 2.dp)) {
        Text(title, color = p.t1, fontSize = qqFontBody(15.sp), fontWeight = FontWeight.Medium)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(QqSpace.xs()))
            Text(subtitle, color = p.t3, fontSize = qqFontBody(12.sp), lineHeight = 18.sp)
        }
    }
}

/** Small section label above a card. */
@Composable
fun SectionTitle(text: String, count: Int? = null) {
    val p = LocalQq.current
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = p.t2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text("$count", color = p.t3, fontSize = 11.sp)
        }
    }
}

/** 分组头 / 分区头共用的 14dp 下向 chevron（stroke 2.6）；收起态向左转 -90° 指向右侧。 */
@Composable
fun SectionChevron(expanded: Boolean, tint: Color = LocalQq.current.t3) {
    val angle by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Icon(
        ChevronDownGlyph,
        contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
        tint = tint,
        modifier = Modifier.size(14.dp).rotate(angle),
    )
}

private val ChevronDownGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "ChevronDown",
        defaultWidth = 14.dp,
        defaultHeight = 14.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }.build()
}

/**
 * 文件夹式分组外框：一个描边框同时包住标题条与内容，视觉上是一张「文件夹」。
 * 与 SessionsScreen 里的分组同源，插件相关页面统一用它。
 */
@Composable
fun GroupFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalQq.current
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(14.dp), clip = false)
            .clip(RoundedCornerShape(14.dp))
            .background(p.card)
            .border(1.dp, p.line, RoundedCornerShape(14.dp)),
        content = content,
    )
}

/** 分组标题条：箭头 + 名称 + 计数，可点折叠；trailing 放按钮/复选。 */
@Composable
fun GroupHeader(
    label: String,
    count: Int? = null,
    expanded: Boolean = true,
    collapsible: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: ( @Composable RowScope.() -> Unit )? = null,
) {
    val p = LocalQq.current
    // 34dp 的固定高既戳不准也会在大字号下压字；改成 48dp 下限，命中区和字体缩放一起解决。
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(p.hover)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsible) {
            SectionChevron(expanded = expanded)
        } else {
            Box(Modifier.width(14.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = p.t2,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.card)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) { Text("$count", color = p.t3, fontSize = 11.sp) }
        }
        trailing?.invoke(this)
    }
}

/** 分组子行：左侧一条树状引导线 + 描边小卡片，点开进入下一层。 */
@Composable
fun GroupChild(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalQq.current
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 11.dp.toPx()
                drawLine(
                    color = p.line,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(start = 22.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(QqShape.chip())
                .background(p.card)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .border(1.dp, p.line, QqShape.chip())
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
