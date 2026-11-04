package dev.dsh.mobile.design

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一级页 / 子页统一顶栏：左导航（汉堡或返回）+ 居中标题 + 右侧操作槽位。
 *
 * 为什么是 `QqScreenTopBar` 而不是 `QqTopBar`：[Components.kt] 里已经有一个同包的
 * `QqTopBar(leading, trailing, leadingExpanded, content)`，14 处在用。这里不重新发明骨架，
 * 只是把「标题怎么放、导航图标怎么选、操作放哪」收敛成一种写法，内部直接委托它——
 * 像素位置因此完全由同一个 Row 决定，迁过来的页面不会漂移。
 *
 * 骨架：汉堡/返回在左，标题正中对齐，操作槽位靠右；副标题可选（没有就完全不占高度）。
 */
@Composable
fun QqScreenTopBar(
    title: String? = null,
    subtitle: String? = null,
    isBack: Boolean = false,
    navigationIcon: ImageVector? = null,
    navigationDescription: String? = null,
    onNavigationClick: () -> Unit = {},
    actions: (@Composable () -> Unit)? = null,
    center: (@Composable () -> Unit)? = null,
) {
    val p = LocalQq.current
    val icon = navigationIcon ?: if (isBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Menu
    QqTopBar(
        leading = {
            IconBtn(icon, navigationDescription ?: if (isBack) stringResource(R.string.common_back) else stringResource(R.string.common_menu), onNavigationClick)
        },
        trailing = actions,
    ) {
        // 会话页中间放的是计数胶囊而不是标题，所以中间槽位可以被整块替换掉；
        // 换进的仍是同一个 Box(weight(1f)) 里的居中槽，像素位置不动。
        if (center != null) {
            center()
        } else if (title != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    color = p.t1,
                    fontSize = qqFontBody(17.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(subtitle, color = p.t3, fontSize = qqFontBody(11.sp), maxLines = 1)
                }
            }
        }
    }
}

/**
 * 页面底部 chrome 的尺寸。tab 栏栏体高度必须和 [QqTabBar] 里那个 `Row(height = 54.dp)` 一致：
 * 那个 Row 属于底部栏组件，拿不到令牌，只能在这里对齐一次。
 */
object QqChrome {
    val TabBarHeight: Dp = 54.dp
}

/**
 * 可滚动页面容器该在底部留多少空白。**每个页面只调这一个函数**，不要各自手摆 dp。
 *
 * `tabBarVisible` 由宿主传入：底部 tab 栏在 [QqTabBar] 里是正常参与布局的兄弟节点
 * （`Column` 的第二个孩子），它自己已经占掉 `54.dp + navigationBars`。这时若再补一遍，
 * 列表尾部会凭空多出一整条空白，所以补 0——真正的遮挡风险来自它**不**在场的时候：
 * 子页、抽屉页、以及键盘弹起。三种情况都在这里算完。
 *
 * 返回值不含设计间距，页面把它加到自己的 contentPadding 上。
 */
@Composable
fun qqPageBottomInset(tabBarVisible: Boolean): Dp {
    val navBars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val chrome = if (tabBarVisible) 0.dp else QqChrome.TabBarHeight + navBars
    return maxOf(chrome, ime)
}
