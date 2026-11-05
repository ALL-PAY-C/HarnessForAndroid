package dev.dsh.mobile.core.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.dsh.mobile.core.model.backup.BackupManifest

/** 包格式版本高于本应用支持上限。message 直接面向用户。 */
class ManifestTooNewException(val schemaVersion: Int) : Exception(
    "备份包格式版本 $schemaVersion 高于本应用支持的 ${BackupManifest.SUPPORTED_SCHEMA_VERSION}，请先升级 App",
)

/** 包内 DB 版本与本机 DB 版本的比较结果。[Newer] 意味着包比本机新，必须拒绝导入。 */
enum class DbVerdict { Newer, Older, Same }

fun compareDb(pack: Int, local: Int): DbVerdict = when {
    pack > local -> DbVerdict.Newer
    pack < local -> DbVerdict.Older
    else -> DbVerdict.Same
}

object BackupManifestCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(m: BackupManifest): String = json.encodeToString(m)

    /**
     * 先只读 [BackupManifest.schemaVersion] 再整体解码：更高版本的包可能把字段改成别的类型，
     * 直接 decode 会抛 SerializationException，那样用户看到的就不是「请先升级 App」。
     */
    fun decode(text: String): BackupManifest {
        val root = json.parseToJsonElement(text).jsonObject
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: BackupManifest.SUPPORTED_SCHEMA_VERSION
        if (version > BackupManifest.SUPPORTED_SCHEMA_VERSION) throw ManifestTooNewException(version)
        return json.decodeFromString<BackupManifest>(text)
    }
}
