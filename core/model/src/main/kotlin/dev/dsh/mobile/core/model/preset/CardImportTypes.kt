package dev.dsh.mobile.core.model.preset

import dev.dsh.mobile.core.model.IdentityCard

// O8-B④: 从 core:data.preset.IdentityCardTransfer 上移（纯数据形状；解析实现仍留在 data）。

/** 一张读出来的卡：id 来自 JSON（可能是空串），落库前重新分配。 */
data class ImportedCard(val id: String, val card: IdentityCard)

sealed interface CardImport {
    data class Ok(val cards: List<ImportedCard>, val skipped: List<CardSkip>) : CardImport
    data class Failed(val reason: CardError) : CardImport
}
