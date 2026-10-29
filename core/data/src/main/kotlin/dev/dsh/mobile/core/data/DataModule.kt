package dev.dsh.mobile.core.data

import android.content.Context
import androidx.room.Room
import dev.dsh.mobile.core.data.db.DshDatabase
import dev.dsh.mobile.core.data.db.EndpointEntity
import dev.dsh.mobile.core.data.repo.ChatRepository
import dev.dsh.mobile.core.data.secret.EndpointSecretStore
import dev.dsh.mobile.core.data.secret.mergeToken
import dev.dsh.mobile.core.data.secret.shouldMigrate
import dev.dsh.mobile.core.data.settings.SettingsStore
import dev.dsh.mobile.core.model.Endpoint
import dev.dsh.mobile.core.model.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual DI: no reflection, no annotation processors, no framework lock-in. */
object DataModule {
    fun createDatabase(context: Context): DshDatabase =
        Room.databaseBuilder(context, DshDatabase::class.java, DshDatabase.NAME)
            .addMigrations(
                DshDatabase.MIGRATION_1_2,
                DshDatabase.MIGRATION_2_3,
                DshDatabase.MIGRATION_3_4,
                DshDatabase.MIGRATION_4_5,
                DshDatabase.MIGRATION_5_6,
                DshDatabase.MIGRATION_6_7,
                DshDatabase.MIGRATION_7_8,
                DshDatabase.MIGRATION_8_9,
                DshDatabase.MIGRATION_9_10,
                DshDatabase.MIGRATION_10_11,
            )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    fun createSettings(context: Context): SettingsStore = SettingsStore(context)

    fun createChatRepository(db: DshDatabase, scope: CoroutineScope): ChatRepository =
        ChatRepository(db, scope)
}

class EndpointRepository(
    private val db: DshDatabase,
    private val settings: SettingsStore,
    private val secretStore: EndpointSecretStore,
) {

    fun observeEndpoints(): Flow<List<Endpoint>> =
        db.endpointDao().observeAll().map { list -> list.map { it.toModel(secretStore) } }

    suspend fun all(): List<Endpoint> = db.endpointDao().all().map { it.toModel(secretStore) }

    suspend fun save(endpoint: Endpoint) {
        // 先持久化密钥到 noBackupFilesDir，成功后才写 Room（Room 行永远落空 token）。
        // 密钥写入失败抛异常 → Room 不更新，旧 token 仍可从 secretStore 读回。
        if (endpoint.token.isBlank()) secretStore.remove(endpoint.id)
        else secretStore.set(endpoint.id, endpoint.token)
        db.endpointDao().upsert(
            EndpointEntity(
                id = endpoint.id,
                name = endpoint.name,
                kind = endpoint.kind.name,
                url = endpoint.url,
                token = "",
                cwd = endpoint.cwd,
                heartbeatMs = endpoint.heartbeatMs,
                autoConnect = if (endpoint.autoConnect) 1 else 0,
                lastUsedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: String) {
        db.endpointDao().delete(id)
        secretStore.remove(id)
    }

    suspend fun setActive(id: String) = settings.setActiveEndpoint(id)

    /** 幂等迁移遗留 token：失败保留旧 token 且可重试。 */
    suspend fun migrateLegacyTokens() = EndpointTokenMigration.run(db, secretStore)
}

private fun EndpointEntity.toModel(store: EndpointSecretStore): Endpoint = Endpoint(
    id = id,
    name = name,
    kind = runCatching { TransportKind.valueOf(kind) }.getOrDefault(TransportKind.Remote),
    url = url,
    token = mergeToken(token, store.get(id)),
    cwd = cwd,
    heartbeatMs = heartbeatMs,
    autoConnect = autoConnect == 1,
)

/**
 * endpoint token 的一次性迁移：把 Room `endpoints.token` 遗留明文复制进 noBackupFilesDir 的
 * [EndpointSecretStore]，持久化成功后才清空 Room 字段。进程内只跑一次（[done]），失败不置位、
 * 可重试；同设备恢复时 secretStore 已有当前值则只清 Room、不覆盖当前 secret。
 */
object EndpointTokenMigration {
    private val mutex = Mutex()
    @Volatile private var done = false

    suspend fun run(db: DshDatabase, store: EndpointSecretStore) {
        if (done) return
        mutex.withLock {
            if (done) return
            val legacy = db.endpointDao().all().filter { it.token.isNotBlank() }
            for (e in legacy) {
                val current = store.get(e.id)
                if (shouldMigrate(e.token, current)) {
                    // 持久化成功才继续；失败抛异常 → 不清 Room → 旧 token 保留、可重试。
                    store.set(e.id, e.token)
                }
                db.endpointDao().clearToken(e.id)
            }
            done = true
        }
    }

    /** 同步屏障：BackupManager.snapshot 读 DB 前调用，确保 legacy token 已迁走（含冷启动 alarm 路径）。 */
    fun ensureMigrated(context: Context) {
        if (done) return
        val db = DataModule.createDatabase(context)
        try {
            runBlocking { run(db, EndpointSecretStore(context)) }
        } finally {
            db.close()
        }
    }

    /** restore 旧备份后调用：DB 被整体覆盖回写，可能带回 legacy token，须重置屏障让下次快照重新迁移。 */
    fun reset() {
        done = false
    }
}
