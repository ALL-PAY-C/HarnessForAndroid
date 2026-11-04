package dev.dsh.mobile.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 温层落库行（T9.8 S1）：一会话一行，[payload] 是 [dev.dsh.mobile.core.model.context.WarmLayer]
 * 的 JSON 全文。
 *
 * 为什么不拆成 design 文档里的十几列：读侧永远是「整层取出来渲染 / 整层覆盖写」，
 * 没有任何一列被单独查询；拆列要多十几处 Entity↔模型映射，换不来查询能力。
 * [coveredUntilMessageId] / [warmVersion] / [source] 冗余成独立列，是为了将来按水位
 * 或来源做 SQL 过滤时不必解 JSON（S2 的去重判定靠它）。
 */
@Entity(tableName = "warm_layers", indices = [Index("updatedAt")])
data class WarmLayerEntity(
    @PrimaryKey val sessionId: String,
    /** WarmLayer 的 JSON 全文，空串表示还没写成功过。 */
    val payload: String = "",
    val updatedAt: Long = 0L,
    /** 已覆盖到的 messages.rowid（0 = 未覆盖）；与 payload 里的字段同值，便于 SQL 过滤。 */
    val coveredUntilMessageId: Long = 0L,
    val warmVersion: Int = 1,
    /** [dev.dsh.mobile.core.model.context.WarmSource] 的 name。 */
    val source: String = "LOCAL",
)
