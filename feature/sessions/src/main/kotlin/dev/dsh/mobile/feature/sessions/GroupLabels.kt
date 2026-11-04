package dev.dsh.mobile.feature.sessions

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.design.R as DesignR

/**
 * 会话分组里的哨兵名同时是 map key 与持久化身份（全部 / 置顶 / 归档 / 最近），
 * 值保持中文不改；只在显示时映射成资源。用户自建分组（未知值）原样返回。
 */
@StringRes
fun sentinelGroupRes(name: String): Int? = when (name) {
    SessionsUiState.ALL_GROUP -> DesignR.string.common_all
    SessionsUiState.PINNED_GROUP -> DesignR.string.common_pinned
    SessionsUiState.ARCHIVE_GROUP -> DesignR.string.sessions_archive
    Session.DEFAULT_GROUP -> DesignR.string.common_recent
    else -> null
}

fun groupLabel(context: Context, name: String): String =
    sentinelGroupRes(name)?.let { context.getString(it) } ?: name

@Composable
fun groupLabel(name: String): String =
    sentinelGroupRes(name)?.let { stringResource(it) } ?: name
