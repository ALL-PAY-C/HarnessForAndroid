package dev.dsh.mobile.app.nav

import dev.dsh.mobile.design.R as DesignR
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.dsh.mobile.app.AppContainer
import dev.dsh.mobile.app.BuildConfig
import dev.dsh.mobile.core.data.db.SessionEntity
import dev.dsh.mobile.core.data.repo.ChatSearchHit
import dev.dsh.mobile.core.model.RuntimeState
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.design.AppIconBadge
import dev.dsh.mobile.design.EmptyState
import dev.dsh.mobile.design.IconBtn
import dev.dsh.mobile.design.LocalQq
import dev.dsh.mobile.design.QqShape
import dev.dsh.mobile.design.QqSpace
import dev.dsh.mobile.design.QqTopBar
import dev.dsh.mobile.design.SlotEditItem
import dev.dsh.mobile.design.SlotEditState
import dev.dsh.mobile.design.SlotPickerDialog
import dev.dsh.mobile.design.SlotRemoveBadge
import dev.dsh.mobile.design.UiEditSlotPlusRow
import dev.dsh.mobile.design.qqFontBody
import dev.dsh.mobile.pluginapi.DrawerEntry
import dev.dsh.mobile.pluginapi.SlotContribution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
@Composable
internal fun DrawerHeader(runtime: RuntimeState) {
    val p = LocalQq.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(p.accent2, p.accent)))
            .statusBarsPadding()
            .padding(start = QqSpace.l(), end = QqSpace.l(), top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(56.dp)
            Spacer(Modifier.width(QqSpace.m()))
            Column {
                Text("DSH Mobile", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (runtime) {
                        is RuntimeState.Ready -> stringResource(DesignR.string.nav_drawer_runtime_ready)
                        is RuntimeState.Starting -> stringResource(DesignR.string.nav_drawer_runtime_starting)
                        is RuntimeState.Reconnecting -> stringResource(DesignR.string.nav_drawer_runtime_reconnecting)
                        is RuntimeState.Failed -> stringResource(DesignR.string.nav_drawer_runtime_failed)
                        RuntimeState.NotConfigured -> stringResource(DesignR.string.nav_drawer_runtime_unconfigured)
                    },
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = qqFontBody(12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun SearchField(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    val p = LocalQq.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                stringResource(DesignR.string.nav_search_placeholder),
                color = p.t3,
                fontSize = qqFontBody(13.5.sp),
            )
        },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = p.t3, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Close,
                        stringResource(DesignR.string.nav_search_clear),
                        tint = p.t3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = qqFontBody(14.sp), color = p.t1),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.line,
            focusedContainerColor = p.bg,
            unfocusedContainerColor = p.bg,
            cursorColor = p.accent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = QqSpace.m(), vertical = 10.dp),
    )
}
