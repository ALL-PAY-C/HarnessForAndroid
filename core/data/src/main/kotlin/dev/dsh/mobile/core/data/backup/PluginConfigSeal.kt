package dev.dsh.mobile.core.data.backup

import dev.dsh.mobile.core.platform.crypto.PassphraseCipher
import java.io.File
import java.util.Base64

/**
 * 插件配置进备份包之前的加密（B2）。
 *
 * 设置节是按「键值原样搬」导出的，所以 `dsh.plugin.packages` 里的 `values.<id>` 会跟着进包。
 * 新版写入的本来就是密文（plugin-host 的 PluginConfigVault），但升级后还没被读过的旧数据仍是明文，
 * 这里在写包前把这类值换成密文 —— 口径与凭证节（BackupWriter 的 `PassphraseCipher.seal`）一致：
 * 同一套 PassphraseCipher，密文才允许进包。
 *
 * 格式与密钥路径必须和 plugin-host 的 PluginConfigVault 完全一致：
 * `dev:v1:<salt b64>:<iv b64>:<blob b64>`，密钥 `noBackupFilesDir/plugin-config.key`（32 字节）。
 * 两个模块互相看不到（core:data 不能依赖 plugin-host），只能靠这份约定对齐，改一处必须同时改另一处；
 * 解密的落点也在那边：恢复写回 prefs 后由 PluginConfigVault 读出来。
 */
object PluginConfigSeal {

    /** 密文前缀：带版本号，将来换算法可以并存识别。 */
    const val PREFIX: String = "dev:v1:"
    const val KEY_FILE_NAME: String = "plugin-config.key"
    private const val KEY_BYTES = 32
    private const val CONFIG_PREFIX = "values."
    private val LOCK = Any()

    fun isSealed(value: String): Boolean = value.startsWith(PREFIX)

    /**
     * 写包前调用：插件包登记文件里的配置值一律以密文进包。
     *
     * 只动 `values.<id>` 且仍是明文的字符串；清单（`installed`）与其他 prefs 文件原样返回，
     * 返回同一实例时调用方不需要多一次拷贝。
     */
    fun sealPrefs(name: String, values: Map<String, Any?>, keyDir: File): Map<String, Any?> {
        if (name != PLUGIN_PACKAGES_PREFS) return values
        var changed = false
        val out = LinkedHashMap<String, Any?>(values.size)
        for ((key, value) in values) {
            if (key.startsWith(CONFIG_PREFIX) && value is String && value.isNotBlank() && !isSealed(value)) {
                out[key] = seal(keyDir, value)
                changed = true
            } else {
                out[key] = value
            }
        }
        return if (changed) out else values
    }

    fun seal(keyDir: File, plain: String): String {
        val sealed = PassphraseCipher.seal(plain.toByteArray(Charsets.UTF_8), passphrase(keyDir))
        val b64 = Base64.getEncoder()
        return PREFIX + b64.encodeToString(sealed.salt) + ":" + b64.encodeToString(sealed.iv) + ":" +
            b64.encodeToString(sealed.blob)
    }

    private fun passphrase(keyDir: File): CharArray =
        Base64.getEncoder().encodeToString(key(keyDir)).toCharArray()

    /** 首次使用生成；同一把锁保证只生成一次（生成两次 = 前一批密文全废）。 */
    private fun key(keyDir: File): ByteArray = synchronized(LOCK) {
        val file = File(keyDir, KEY_FILE_NAME)
        val existing = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
        if (existing != null && existing.size == KEY_BYTES) return existing
        val fresh = PassphraseCipher.randomBytes(KEY_BYTES)
        keyDir.mkdirs()
        file.writeBytes(fresh)
        fresh
    }
}
