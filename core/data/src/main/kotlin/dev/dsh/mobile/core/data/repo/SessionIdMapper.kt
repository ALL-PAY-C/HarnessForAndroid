package dev.dsh.mobile.core.data.repo

import java.util.concurrent.atomic.AtomicLong

/**
 * M2：本地会话 id ↔ ACP 远端会话 id 的双向映射 + 连接作用域（端点身份 + 代际）。
 *
 * - 正向（发请求）：本地 id → 远端 id（prompt/cancel/interject/setConfigOption/replyPermission）。
 *   无映射返回 null（B2：绝不回退本地 id 发远端）。
 * - 反向（收事件）：远端 id → 本地 id（所有 DshEvent.sessionId + Permission.request.sessionId）。
 *   无映射返回 null（调用方据此丢弃孤儿事件，绝不原样回退本地 id）。
 * - 端点隔离（B1）：[switch] 之后只接受「当前端点」的映射，旧端点 remoteId 不注册、不匹配。
 * - 连接代际（G2）：[switch] 每次代际 +1；[isCurrent] 判定旧连接在飞事件/结果作废。
 * - 共同锁：switch/register/unregister/读 snapshot 全在同一把锁下，双表（toRemote/toLocal）
 *   恒一致，杜绝 switch 与 register 并发造成的「一张表清了、另一张没清」。
 */
internal class SessionIdMapper {
    private val lock = Any()
    private val toRemote = HashMap<String, String>()
    private val toLocal = HashMap<String, String>()
    @Volatile private var endpointId: String? = null
    private val generation = AtomicLong(0)

    /** 换连接/断连：代际 +1、换端点、清空映射。旧代际翻译与注册全部作废。 */
    fun switch(endpointId: String?) {
        synchronized(lock) {
            generation.incrementAndGet()
            this.endpointId = endpointId
            toRemote.clear()
            toLocal.clear()
        }
    }

    val currentEndpointId: String? get() = endpointId
    val currentGeneration: Long get() = generation.get()
    fun isCurrent(gen: Long): Boolean = generation.get() == gen

    /**
     * 注册映射。仅当「端点 == 当前端点」且「代际 == 当前代际」同时成立才写（B1/G2 同锁校验），
     * 否则返回 false（旧端点/旧代际映射绝不注册）。[entityEndpointId] 为持久化行的 endpointId，
     * 不默认绑当前端点：endpointId=null 的 legacy 行不注册。远端 id 是任意字符串（可以是 UUID），
     * 不再排除 remoteId==localId。
     */
    fun register(localId: String, remoteId: String, entityEndpointId: String?, gen: Long): Boolean {
        synchronized(lock) {
            if (gen != generation.get()) return false
            // legacy 行无端点归属（entityEndpointId=null）：显式拒绝，绝不注册（即使 current 端点也为 null）。
            if (entityEndpointId == null) return false
            if (entityEndpointId != endpointId) return false
            if (remoteId.isBlank()) return false
            // 双射维护：先移除两侧旧对应，再写新映射；杜绝同 local 换 remote / 同 remote 换 local 的残留。
            toRemote.remove(localId)?.let { toLocal.remove(it) }
            toLocal.remove(remoteId)?.let { toRemote.remove(it) }
            toRemote[localId] = remoteId
            toLocal[remoteId] = localId
            return true
        }
    }

    fun unregisterLocal(localId: String) {
        synchronized(lock) {
            toRemote.remove(localId)?.let { toLocal.remove(it) }
        }
    }

    /** 正向：本地 id → 远端 id；无映射返回 null（绝不回退本地 id）。 */
    fun remote(localId: String): String? = synchronized(lock) { toRemote[localId] }

    /** 反向：远端 id → 本地 id；无映射返回 null（调用方据此丢弃孤儿事件）。 */
    fun local(remoteId: String): String? = synchronized(lock) { toLocal[remoteId] }

    /** 原子读双表快照（测试断言双表一致性用）。 */
    fun snapshot(): Pair<Map<String, String>, Map<String, String>> = synchronized(lock) {
        toRemote.toMap() to toLocal.toMap()
    }
}
