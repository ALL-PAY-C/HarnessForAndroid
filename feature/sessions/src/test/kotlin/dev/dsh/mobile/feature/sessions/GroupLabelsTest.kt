package dev.dsh.mobile.feature.sessions

import dev.dsh.mobile.core.model.Session
import dev.dsh.mobile.design.R as DesignR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 哨兵分组名是持久化身份（中文不改），显示层映射成资源；用户自建分组原样显示。 */
class GroupLabelsTest {

    @Test
    fun sentinelGroupsMapToResources() {
        assertEquals(DesignR.string.common_all, sentinelGroupRes(SessionsUiState.ALL_GROUP))
        assertEquals(DesignR.string.common_pinned, sentinelGroupRes(SessionsUiState.PINNED_GROUP))
        assertEquals(DesignR.string.sessions_archive, sentinelGroupRes(SessionsUiState.ARCHIVE_GROUP))
        assertEquals(DesignR.string.common_recent, sentinelGroupRes(Session.DEFAULT_GROUP))
    }

    @Test
    fun unknownGroupStaysVerbatim() {
        assertNull(sentinelGroupRes("work"))
        assertNull(sentinelGroupRes("工作/紧急"))
        assertNull(sentinelGroupRes(""))
        // 前缀/近似值都不能误命中：身份是精确字符串。
        assertNull(sentinelGroupRes("全部 "))
        assertNull(sentinelGroupRes("归档·旧"))
    }
}
