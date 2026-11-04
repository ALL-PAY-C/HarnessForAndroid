package dev.dsh.mobile.core.data.context

import dev.dsh.mobile.core.data.db.WarmLayerDao
import dev.dsh.mobile.core.data.db.WarmLayerEntity
import dev.dsh.mobile.core.model.context.WarmLayer
import dev.dsh.mobile.core.model.context.WarmLayerStore
import dev.dsh.mobile.core.model.context.WarmSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 温层的 Room 实现（T9.8 S1）。
 *
 * 只做「整层存取 + 懒回填判据」：不做合并、不调模型、不碰装配 —— 那些是 S2/S3。
 * 时钟由构造参数注入，单测传固定值就能断言 updatedAt 盖章。
 */
class RoomWarmLayerStore(
    private val dao: WarmLayerDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : WarmLayerStore {

    override fun observe(sessionId: String): Flow<WarmLayer?> =
        dao.observe(sessionId).map { entity -> entity?.let { WarmLayerCodec.decode(it.payload) } }

    override suspend fun read(sessionId: String): WarmLayer? =
        dao.get(sessionId)?.let { WarmLayerCodec.decode(it.payload) }

    /**
     * 装配入口。库里没有行时**不写库、不调模型**：
     * 有历史消息 → 返回空层（source=LOCAL、水位 0），让 S2 之后按需生成；
     * 连历史都没有（新会话）→ null。
     */
    override suspend fun layer(sessionId: String): WarmLayer? =
        read(sessionId) ?: if (dao.messageCount(sessionId) > 0) WarmLayer.EMPTY else null

    override suspend fun write(sessionId: String, layer: WarmLayer) {
        require(sessionId.isNotBlank()) { "会话 id 不能为空" }
        val stamped = layer.copy(updatedAt = clock())
        dao.upsert(
            WarmLayerEntity(
                sessionId = sessionId,
                payload = WarmLayerCodec.encode(stamped),
                updatedAt = stamped.updatedAt,
                coveredUntilMessageId = stamped.coveredUntilMessageId,
                warmVersion = stamped.warmVersion,
                source = stamped.source.name,
            ),
        )
    }

    override suspend fun clear(sessionId: String) = dao.delete(sessionId)
}

/**
 * [WarmLayer] 与 warm_layers 行之间的 JSON 编解码。
 *
 * 解不开就当没有温层（返回 null）：宁可在 S3 退回「无温层」路径，也不要让一行坏 JSON
 * 把整轮装配打挂。全部字段有默认值，缺字段的老 JSON 照样解得出来。
 */
internal object WarmLayerCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(layer: WarmLayer): String = json.encodeToString(WarmLayer.serializer(), layer)

    fun decode(payload: String): WarmLayer? =
        runCatching { json.decodeFromString(WarmLayer.serializer(), payload) }.getOrNull()

    /** 行的冗余列（水位 / 版本 / 来源）只用于 SQL 过滤；读不到 payload 时退回它们拼一个空层。 */
    fun decode(entity: WarmLayerEntity): WarmLayer? =
        decode(entity.payload) ?: entity.takeIf { it.payload.isBlank() }?.let {
            WarmLayer(
                coveredUntilMessageId = it.coveredUntilMessageId,
                warmVersion = it.warmVersion,
                source = runCatching { WarmSource.valueOf(it.source) }.getOrDefault(WarmSource.LOCAL),
                updatedAt = it.updatedAt,
            )
        }
}
