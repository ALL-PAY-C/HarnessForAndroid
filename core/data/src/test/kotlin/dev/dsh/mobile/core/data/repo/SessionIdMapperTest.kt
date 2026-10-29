package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.data.db.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdMapperTest {

    @Test
    fun `register maps local to remote and back`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g = m.currentGeneration
        m.register("local-1", "remote-1", "endpoint-A", g)
        assertEquals("remote-1", m.remote("local-1"))
        assertEquals("local-1", m.local("remote-1"))
    }

    @Test
    fun `remote returns null when unmapped (B2 no fallback)`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        assertNull(m.remote("anything"))
    }

    @Test
    fun `local returns null when unmapped (no fallback to input)`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        assertNull(m.local("anything"))
    }

    @Test
    fun `switch clears mappings and bumps generation (G2)`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g1 = m.currentGeneration
        m.register("l1", "r1", "endpoint-A", g1)
        assertEquals("r1", m.remote("l1"))

        m.switch("endpoint-B")
        val g2 = m.currentGeneration
        assertTrue(g2 > g1)
        assertNull(m.remote("l1"))          // 映射清空
        assertNull(m.local("r1"))           // 反向也清空（无映射返回 null）
        assertFalse(m.isCurrent(g1))        // 旧代际失效
        assertTrue(m.isCurrent(g2))         // 新代际有效
    }

    @Test
    fun `register ignores mapping from a stale endpoint (B1)`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g = m.currentGeneration
        m.register("l1", "r1", "endpoint-A", g)   // 当前端点：注册
        assertEquals("r1", m.remote("l1"))

        // 模拟 reload 时读到旧端点行：endpointId 与当前不符，不注册。
        assertFalse(m.register("l2", "r2", "endpoint-STALE", g))
        assertNull(m.remote("l2"))
    }

    @Test
    fun `register rejects legacy endpointId null (B1 no default bind to current)`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g = m.currentGeneration
        // legacy 行 endpointId=null：不默认绑当前端点，绝不注册。
        assertFalse(m.register("l1", "r1", null, g))
        assertNull(m.remote("l1"))
        assertNull(m.local("r1"))
    }

    @Test
    fun `register rejects null endpoint even when current endpoint is also null`() {
        val m = SessionIdMapper()
        m.switch(null)                    // 本地引擎 / 未连接：current 端点也为 null
        val g = m.currentGeneration
        // entityEndpointId=null 显式拒绝：不因 null==null 放行。
        assertFalse(m.register("l1", "r1", null, g))
        assertNull(m.remote("l1"))
        assertNull(m.local("r1"))
    }

    @Test
    fun `register keeps bijection when same local gets a new remote (no stale reverse)`() {
        val m = SessionIdMapper()
        m.switch("ep")
        val g = m.currentGeneration
        m.register("L", "R1", "ep", g)
        m.register("L", "R2", "ep", g)   // 同 local 换 remote
        assertEquals("R2", m.remote("L"))
        assertEquals("L", m.local("R2"))
        assertNull(m.local("R1"))        // 旧 reverse 已清
    }

    @Test
    fun `register keeps bijection when same remote gets a new local (no stale forward)`() {
        val m = SessionIdMapper()
        m.switch("ep")
        val g = m.currentGeneration
        m.register("L1", "R", "ep", g)
        m.register("L2", "R", "ep", g)   // 同 remote 换 local
        assertEquals("L2", m.local("R"))
        assertEquals("R", m.remote("L2"))
        assertNull(m.remote("L1"))       // 旧 forward 已清
    }

    @Test
    fun `old endpoint mapping not carried to new endpoint (B1)`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g1 = m.currentGeneration
        m.register("l1", "r1", "endpoint-A", g1)
        assertEquals("r1", m.remote("l1"))

        m.switch("endpoint-B")                 // 换端点
        val g2 = m.currentGeneration
        assertFalse(m.register("l1", "r1", "endpoint-A", g2))  // 旧端点行重放：不注册
        assertNull(m.remote("l1"))             // 旧 remoteId 绝不串到新端点
    }

    @Test
    fun `register rejects stale generation on same endpoint (G2)`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g1 = m.currentGeneration
        m.switch("endpoint-A")                 // 同端点再切：代际 +1，endpoint 不变
        assertFalse(m.isCurrent(g1))
        assertFalse(m.register("l1", "r1", "endpoint-A", g1))  // 旧代际：拒绝
        assertNull(m.remote("l1"))
    }

    @Test
    fun `register ignores blank remote id but allows self id (arbitrary string)`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g = m.currentGeneration
        assertFalse(m.register("l", "", "endpoint-A", g))      // blank 拒绝
        assertTrue(m.register("same", "same", "endpoint-A", g)) // 去掉 remoteId==localId 限制
        assertEquals("same", m.remote("same"))
        assertEquals("same", m.local("same"))
    }

    @Test
    fun `register allows remote id equal to a UUID string`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g = m.currentGeneration
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        assertTrue(m.register("local-1", uuid, "endpoint-A", g))
        assertEquals(uuid, m.remote("local-1"))
        assertEquals("local-1", m.local(uuid))
    }

    @Test
    fun `unregisterLocal removes both directions`() {
        val m = SessionIdMapper()
        m.switch("endpoint-A")
        val g = m.currentGeneration
        m.register("l1", "r1", "endpoint-A", g)
        m.unregisterLocal("l1")
        assertNull(m.remote("l1"))
        assertNull(m.local("r1"))
    }

    @Test
    fun `register writes both tables atomically (dual table snapshot)`() {
        val m = SessionIdMapper()
        m.switch("ep")
        val g = m.currentGeneration
        m.register("l1", "r1", "ep", g)
        m.register("l2", "r2", "ep", g)
        val (toRemote, toLocal) = m.snapshot()
        assertEquals("r1", toRemote["l1"])
        assertEquals("l1", toLocal["r1"])
        assertEquals("r2", toRemote["l2"])
        assertEquals("l2", toLocal["r2"])
    }

    @Test
    fun `concurrent register switch unregister keep dual tables consistent`() {
        val m = SessionIdMapper()
        val threads = (1..8).map { n ->
            Thread {
                repeat(1000) { i ->
                    val ep = "ep-${n % 2}"
                    m.switch(ep)
                    val g = m.currentGeneration
                    m.register("l-$n", "r-$n-$i", ep, g)
                    m.unregisterLocal("l-$n")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val (toRemote, toLocal) = m.snapshot()
        // 双表恒一致：toRemote 每条都有对应 toLocal，反之亦然（共同锁杜绝「一张表清了另一张没清」）。
        toRemote.forEach { (k, v) -> assertEquals("toLocal 缺 $v（toRemote[$k]=$v）", k, toLocal[v]) }
        toLocal.forEach { (k, v) -> assertEquals("toRemote 缺 $v（toLocal[$k]=$v）", k, toRemote[v]) }
    }

    @Test
    fun `endpointId and remoteId survive toModel-toEntity round trip`() {
        val e = SessionEntity(id = "local", remoteId = "remote", endpointId = "ep")
        assertEquals("remote", e.toModel().remoteId)
        assertEquals("ep", e.toModel().endpointId)
        assertEquals("remote", e.toModel().toEntity().remoteId)
        assertEquals("ep", e.toModel().toEntity().endpointId)
    }
}
