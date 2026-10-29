package dev.dsh.mobile.core.data.repo

import dev.dsh.mobile.core.model.TokenUsage
import dev.dsh.mobile.core.protocol.DshEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAccountingTest {
    @Test fun cachedAndLastPromptSurviveDeltaAndEntityMapping() {
        val previous = TokenUsage(input = 60, output = 12, cached = 40, lastPrompt = 100)
        val request = DshEvent.Usage("s", TokenUsage(input = 40, output = 7, cached = 10, lastPrompt = 50))
        val delta = usageDelta(previous, request)
        assertEquals(request.usage, delta)
        val total = previous + delta
        val entity = dev.dsh.mobile.core.data.db.SessionEntity(
            id = "s", title = "test", inputTokens = total.input, outputTokens = total.output,
            cachedTokens = total.cached, lastPrompt = total.lastPrompt,
        )
        assertEquals(total, entity.toModel().usage)
        assertEquals(169L, entity.toModel().usage.sessionTotal)
        assertEquals(50L, entity.toModel().usage.lastPrompt)
        assertEquals(0L, dev.dsh.mobile.core.data.db.SessionEntity(id = "old", title = "old").toModel().usage.lastPrompt)
        val cumulative = DshEvent.Usage("s", total, cumulative = true)
        assertEquals(delta, usageDelta(previous, cumulative))
    }

    @Test fun migrationAddsColumnsWithoutReplacingSessions() {
        val statements = mutableListOf<String>()
        val database = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        val migration = dev.dsh.mobile.core.data.db.DshDatabase.MIGRATION_10_11
        assertEquals(10, migration.startVersion)
        assertEquals(11, migration.endVersion)
        migration.migrate(database)
        assertEquals(listOf(
            "ALTER TABLE sessions ADD COLUMN cachedTokens INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE sessions ADD COLUMN lastPrompt INTEGER NOT NULL DEFAULT 0",
        ), statements)
    }

    @Test fun smallerSecondAuthorStillCountsEveryRequest() {
        val a = DshEvent.Usage("s", TokenUsage(100, 20), "a")
        val b = DshEvent.Usage("s", TokenUsage(80, 10), "b")
        val state = TurnState().recordUsage(a.usage, a.authorId).recordUsage(b.usage, b.authorId)
        assertEquals(180L, state.inputTokens)
        assertEquals(30L, state.outputTokens)
        assertEquals(80L, state.parts.first { it.id == "b" }.inputTokens)
        assertEquals(TokenUsage(80, 10), usageDelta(TokenUsage(100, 20), b))
    }

    @Test fun sameAuthorToolRoundsAddEvenWhenReportsAreEqual() {
        val state = TurnState().recordUsage(TokenUsage(100, 20), "a")
            .recordUsage(TokenUsage(120, 30), "a").recordUsage(TokenUsage(120, 30), "a")
        assertEquals(340L, state.inputTokens)
        assertEquals(80L, state.parts.single().outputTokens)
    }

    @Test fun cumulativeSessionSnapshotsDeduplicateAcrossUserTurns() {
        val event = DshEvent.Usage("s", TokenUsage(150, 35), cumulative = true)
        assertEquals(TokenUsage(50, 15), usageDelta(TokenUsage(100, 20), event))
        assertEquals(TokenUsage.EMPTY, usageDelta(TokenUsage(150, 35), event))
        assertEquals(TokenUsage.EMPTY, usageDelta(TokenUsage(180, 40), event))
        val freshTurn = TurnState().recordUsage(usageDelta(TokenUsage(100, 20), event), null)
        assertEquals(50L, freshTurn.inputTokens)
    }
}
