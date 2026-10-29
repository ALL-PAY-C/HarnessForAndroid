package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.protocol.DshEvent

/** ACP reports session snapshots; the local engine reports one request at a time. */
internal fun usageDelta(previous: TokenUsage, event: DshEvent.Usage): TokenUsage = TokenUsage(
    input = (event.usage.input - if (event.cumulative) previous.input else 0L).coerceAtLeast(0L),
    output = (event.usage.output - if (event.cumulative) previous.output else 0L).coerceAtLeast(0L),
    cached = (event.usage.cached - if (event.cumulative) previous.cached else 0L).coerceAtLeast(0L),
    lastPrompt = event.usage.lastPrompt,
)

internal fun TurnState.recordUsage(delta: TokenUsage, authorId: String?): TurnState {
    val next = copy(inputTokens = inputTokens + delta.input, outputTokens = outputTokens + delta.output)
    if (authorId == null) return next
    val part = parts.firstOrNull { it.id == authorId }
    return if (part != null) next.copy(parts = parts.map {
        if (it.id == authorId) it.copy(inputTokens = it.inputTokens + delta.input, outputTokens = it.outputTokens + delta.output) else it
    }) else {
        val seq = partSeq[authorId] ?: 0
        next.copy(
            parts = parts + AuthorPart(id = authorId, name = authorId.take(6), seq = seq,
                inputTokens = delta.input, outputTokens = delta.output),
            partSeq = partSeq + (authorId to seq + 1),
        )
    }
}
