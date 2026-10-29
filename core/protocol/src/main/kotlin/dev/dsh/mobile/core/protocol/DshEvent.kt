package dev.dsh.mobile.core.protocol

import dev.dsh.mobile.core.model.PermissionRequest
import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.model.ToolCall

/** Normalized stream of things the UI cares about. */
sealed interface DshEvent {
    /** [authorId]/[authorName] are set in group chats so each agent streams into its own bubble. */
    data class MessageChunk(
        val sessionId: String,
        val text: String,
        val authorId: String? = null,
        val authorName: String? = null,
        /** 事件所属轮次的代际 id；null = ACP / 旧生产者（兼容，不做代际过滤）。 */
        val turnId: String? = null,
    ) : DshEvent
    data class ThoughtChunk(
        val sessionId: String,
        val text: String,
        val authorId: String? = null,
        val authorName: String? = null,
        val turnId: String? = null,
    ) : DshEvent
    data class ToolCallEvent(
        val sessionId: String,
        val call: ToolCall,
        val authorId: String? = null,
        val authorName: String? = null,
        val turnId: String? = null,
    ) : DshEvent
    data class Plan(val sessionId: String, val steps: List<String>, val turnId: String? = null) : DshEvent
    data class Usage(
        val sessionId: String,
        val usage: TokenUsage,
        val authorId: String? = null,
        /** true when the producer reports a cumulative session snapshot (ACP); false for one-request usage. */
        val cumulative: Boolean = false,
        val turnId: String? = null,
    ) : DshEvent
    data class Commands(val sessionId: String, val commands: List<String>) : DshEvent
    data class ConfigOptions(val sessionId: String, val options: List<ConfigOption>) : DshEvent
    data class Permission(val sessionId: String, val request: PermissionRequest) : DshEvent
    data class PermissionResolved(val sessionId: String, val requestId: String) : DshEvent
    /** [authorId] null = the whole turn finished; non-null = that group member finished. [turnId] 让结算按代际幂等。 */
    data class TurnEnd(val sessionId: String, val reason: String, val authorId: String? = null, val turnId: String? = null) : DshEvent
    /** Unknown notification, forwarded for plugin/extension consumers. */
    data class Raw(val method: String, val params: kotlinx.serialization.json.JsonObject) : DshEvent
}

data class ConfigOption(
    val id: String,
    val name: String,
    val currentValue: String,
    val values: List<OptionValue> = emptyList(),
)

data class OptionValue(val value: String, val label: String)
