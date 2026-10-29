package dev.dsh.mobile.app.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class BackNavTest {

    private val tabRoutes = listOf("sessions", "agents", "plugins/hub", "settings", "plugin/demo")

    @Test
    fun tab_roots_leave_back_to_the_system() {
        tabRoutes.forEach { route ->
            assertEquals("$route 是 tab 根，返回键交给系统", BackTarget.System, backTarget(route, tabRoutes))
        }
    }

    @Test
    fun sub_pages_pop_their_own_route() {
        listOf("drawer/phone", "drawer/skills", "plugins", "plugins/slots", "terminal").forEach { route ->
            assertEquals("$route 是二级页", BackTarget.PopRoute, backTarget(route, tabRoutes))
        }
    }

    /** 核心回归：二级页返回必须 popBackStack，不能落到 startDestination(sessions)。 */
    @Test
    fun sub_page_back_never_falls_through_to_session_home() {
        listOf("drawer/phone", "drawer/presets", "plugins", "plugins/slots", "terminal").forEach { route ->
            assertEquals("$route 不许穿透到会话主页", BackTarget.PopRoute, backTarget(route, tabRoutes))
        }
    }

    @Test
    fun unknown_route_is_treated_as_sub_page() {
        assertEquals(BackTarget.PopRoute, backTarget("plugin/demo/detail", tabRoutes))
        assertEquals(BackTarget.PopRoute, backTarget("chat/s1", tabRoutes))
    }

    @Test
    fun missing_route_is_left_to_the_system() {
        assertEquals(BackTarget.System, backTarget(null, tabRoutes))
        assertEquals(BackTarget.System, backTarget("", tabRoutes))
    }

    @Test
    fun in_page_navigation_wins_over_routing() {
        assertEquals(BackTarget.InPage, backTarget("plugins/hub", tabRoutes, inPageDepth = 1))
        assertEquals(BackTarget.InPage, backTarget(null, tabRoutes, inPageDepth = 2))
    }
}
