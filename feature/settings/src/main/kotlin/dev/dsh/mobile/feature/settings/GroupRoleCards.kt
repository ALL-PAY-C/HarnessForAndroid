package dev.dsh.mobile.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.core.data.group.GroupRole
import dev.dsh.mobile.core.data.preset.AgentPresetStore
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqCardDivider
import dev.dsh.mobile.design.QqIdentityCardEditor
import dev.dsh.mobile.design.qqFontBody
import kotlinx.coroutines.launch

/**
 * 设置 → 群聊 → 角色卡（T9.7a）：四个固定席位 + 自定义，每个席位一张身份卡。
 *
 * 卡片本身是覆盖层（[AgentPresetStore.setRoleCard]），默认卡写死在 [GroupRole.defaultCard]；
 * 「恢复默认」只清覆盖层，所以默认卡的改动会随版本升级自动生效。
 * 编辑器与「智能体预设 → 身份卡」共用 design 里的 [QqIdentityCardEditor]，不复制表单。
 */
@Composable
internal fun GroupRoleCardsSection(store: AgentPresetStore) {
    val p = LocalQq.current
    val scope = rememberCoroutineScope()
    val overrides by store.roleCards.collectAsStateWithLifecycle(initialValue = emptyMap())
    var editing by remember { mutableStateOf<GroupRole?>(null) }

    GroupRole.entries.forEachIndexed { index, role ->
        val card = overrides[role.id] ?: role.defaultCard()
        val customized = overrides.containsKey(role.id)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { editing = if (editing == role) null else role }
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(role.label, color = p.t1, fontSize = qqFontBody(13.sp), fontWeight = FontWeight.Medium)
                    if (customized) {
                        Spacer(Modifier.width(6.dp))
                        Text("已改", color = p.accent, fontSize = qqFontBody(10.sp))
                    }
                }
                Text(
                    card.identity.ifBlank { card.name }.ifBlank { "（空卡）" },
                    color = p.t3,
                    fontSize = qqFontBody(11.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { editing = if (editing == role) null else role }) {
                Text(if (editing == role) "收起" else "身份卡", fontSize = 12.sp)
            }
        }
        if (editing == role) {
            QqIdentityCardEditor(
                initial = card,
                busy = false,
                onSave = { next -> scope.launch { store.setRoleCard(role, next) } },
                hint = "默认卡随版本升级；改过的卡存在本机覆盖层，不修改内置本体。",
                clearLabel = "恢复默认",
                clearHint = "恢复默认只清掉这张卡的覆盖层，之后跟随版本内置文案。",
            )
        }
        if (index != GroupRole.entries.lastIndex) QqCardDivider()
    }
}
