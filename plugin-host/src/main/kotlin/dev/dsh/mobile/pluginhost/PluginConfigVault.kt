package dev.dsh.mobile.pluginhost

import android.content.Context
import dev.dsh.mobile.core.platform.crypto.PassphraseCipher
import java.io.File
import java.util.Base64

/**
 * 插件配置的静态加密（B2）。
 *
 * 磁盘形态（`dsh.plugin.packages` 里的 `values.<id>`）：
 * `dev:v1:<salt b64>:<iv b64>:<blob b64>`，密文覆盖整段配置 JSON，
 * 所以键名（apiKey / token 之类）也不落在明文里。
 *
 * 算法不新写：复用 [PassphraseCipher]（PBKDF2WithHmacSHA256 120000 → AES/GCM/NoPadding，16 字节 tag），
 * 口令是本地密钥的 Base64，每次 seal 换新的 salt/iv。
 *
 * 密钥来源：`noBackupFilesDir/plugin-config.key`，首次使用时用 [PassphraseCipher.randomBytes] 生成 32 字节。
 * 选 noBackupFilesDir 与 [dev.dsh.mobile.core.data.secret.EndpointSecretStore] 同一约定：不进 Android
 * Auto Backup，也不进本 App 自己的备份包 —— 换机 / 清数据后密钥随之丢失，旧密文解不开，
 * 读路径按「没有配置」处理（不回退明文、不崩），用户重填即可。
 *
 * 与 core:data 的 backup/PluginConfigSeal.kt 必须保持同一格式与同一密钥路径：
 * plugin-host 看不到 core:data（依赖方向相反），只能靠这份约定对齐，改一处要同时改另一处。
 *
 * 性能（P1）：PBKDF2-120k 单次在 Android 上是几十毫秒量级，而配置读写发生在 UI 回调里（每次按键），
 * 所以密钥字节与口令（Base64）只取一次并缓存；[seal] 仍每次派生（新 salt/iv 是密文的一部分），
 * 但读路径在 [InstalledPlugins] 有缓存后根本不会调到这里。
 */
internal class PluginConfigVault(private val keyFile: File) {

    /** 密钥与口令的内存缓存：输入（文件内容）不变就没有理由每次重新读盘、重新 Base64。 */
    @Volatile
    private var cachedKey: ByteArray? = null

    @Volatile
    private var cachedPassphrase: CharArray? = null

    /** 单测用计数：证明热路径不再重复派生 / 重复读盘。 */
    internal var openCount: Int = 0
        private set

    internal var sealCount: Int = 0
        private set

    internal var keyLoads: Int = 0
        private set

    /** 加密；密钥不可用（目录不可写）时抛异常 —— 调用方宁可不写，也不把明文落盘。 */
    fun seal(plain: String): String {
        sealCount++
        val sealed = PassphraseCipher.seal(plain.toByteArray(Charsets.UTF_8), passphrase())
        return PREFIX + b64(sealed.salt) + ":" + b64(sealed.iv) + ":" + b64(sealed.blob)
    }

    /** 解不开（换机、密钥丢失、密文被改）一律 null：调用方按「没有配置」处理。 */
    fun open(sealed: String): String? {
        openCount++
        return runCatching {
            val parts = sealed.removePrefix(PREFIX).split(':')
            if (parts.size != 3) return null
            String(
                PassphraseCipher.open(
                    Base64.getDecoder().decode(parts[2]),
                    passphrase(),
                    Base64.getDecoder().decode(parts[0]),
                    Base64.getDecoder().decode(parts[1]),
                ),
                Charsets.UTF_8,
            )
        }.getOrNull()
    }

    /** 口令 = 密钥的 Base64；只算一次。 */
    private fun passphrase(): CharArray =
        cachedPassphrase ?: synchronized(LOCK) {
            cachedPassphrase ?: b64(key()).toCharArray().also { cachedPassphrase = it }
        }

    /** 首次使用生成；同一把锁保证只生成一次（生成两次 = 前一批密文全废）。之后走内存。 */
    private fun key(): ByteArray =
        cachedKey ?: synchronized(LOCK) {
            cachedKey ?: loadKey().also { cachedKey = it }
        }

    private fun loadKey(): ByteArray {
        keyLoads++
        val existing = runCatching { keyFile.takeIf { it.isFile }?.readBytes() }.getOrNull()
        if (existing != null && existing.size == KEY_BYTES) return existing
        val fresh = PassphraseCipher.randomBytes(KEY_BYTES)
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(fresh)
        return fresh
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        /** 密文前缀：带版本号，将来换算法可以并存识别。 */
        const val PREFIX: String = "dev:v1:"
        const val KEY_FILE_NAME: String = "plugin-config.key"
        private const val KEY_BYTES = 32
        private val LOCK = Any()

        fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

        /**
         * 按 Context 定位密钥文件。JVM 单测里 android.jar 是桩、目录方法返回 null，
         * 所以逐级回退；回退只影响测试进程，真机上第一档必然命中。
         */
        fun forContext(context: Context): PluginConfigVault {
            val dir = runCatching { context.noBackupFilesDir }.getOrNull()
                ?: runCatching { context.filesDir }.getOrNull()
                ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            return PluginConfigVault(File(dir, KEY_FILE_NAME))
        }
    }
}
