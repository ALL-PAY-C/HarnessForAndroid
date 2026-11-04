package dev.dsh.mobile.core.model.context

import kotlinx.coroutines.flow.Flow

/**
 * 温层读写通道：装配层（core:protocol）只认这几个动作，不认 Room / DataStore。
 *
 * 与 [dev.dsh.mobile.core.model.GroupLedgerSink] 同一套路：正式实现住在 core:data
 * （RoomWarmLayerStore），单测传内存实现，于是 core:protocol 不需要数据库依赖。
 *
 * 约定：
 * - 没有温层行时 [read] / [observe] 给 null，不凭空造对象；
 * - [layer] 是装配入口：库里没有行但该会话有历史消息时，返回 [WarmLayer.EMPTY] 形状的空层
 *   （source=LOCAL、coveredUntilMessageId=0），**不写库、不调模型**，真正的生成留给 S2 的懒回填。
 */
interface WarmLayerStore {
    /** 观察单个会话的温层；没有行时推 null。 */
    fun observe(sessionId: String): Flow<WarmLayer?>

    /** 读一次；没有行给 null。 */
    suspend fun read(sessionId: String): WarmLayer?

    /** 装配入口：没有行但有历史 → 空温层；连历史都没有 → null。 */
    suspend fun layer(sessionId: String): WarmLayer?

    /** 整层写入（同会话覆盖）；[WarmLayer.updatedAt] 由实现盖章。 */
    suspend fun write(sessionId: String, layer: WarmLayer)

    /** 删掉该会话的温层（会话被清空 / 删除时调用）。 */
    suspend fun clear(sessionId: String)
}
