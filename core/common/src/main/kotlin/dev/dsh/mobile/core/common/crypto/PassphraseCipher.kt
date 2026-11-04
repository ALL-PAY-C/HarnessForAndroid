package dev.dsh.mobile.core.common.crypto

import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 口令错误：AES-GCM 的 tag 校验失败。
 *
 * 单独一个类型是为了让上层能把它翻成「口令错误」而不是「备份包损坏」——两者的用户动作完全不同。
 */
class WrongPassphraseException(cause: Throwable? = null) :
    Exception("口令错误：包内凭证无法解密", cause)

/**
 * 备份包凭证节的口令加密（设计见 research/T913-backup.md §2.5）。
 *
 * PBKDF2WithHmacSHA256(120000) 派生 256-bit key → AES/GCM/NoPadding，16 字节 tag。
 * 只做「一把口令加密一段字节」，不碰磁盘、不碰日志、不持有口令：
 * salt/iv 由调用方随包存（[Sealed]），口令用完即由调用方清零。
 */
object PassphraseCipher {

    const val ITERATIONS: Int = 120_000
    const val KEY_BITS: Int = 256
    const val SALT_BYTES: Int = 16
    const val IV_BYTES: Int = 12
    const val TAG_BITS: Int = 128

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KDF = "PBKDF2WithHmacSHA256"
    private val random = SecureRandom()

    /** 加密结果：密文（含 tag）+ 本次用的 salt 与 iv，三者都要进 manifest。 */
    class Sealed(val blob: ByteArray, val salt: ByteArray, val iv: ByteArray)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /**
     * 加密 [plain]。口令为空直接拒绝（空口令 = 无加密，不能装作加密了）。
     * 每次调用都换新的 salt/iv —— 同一明文两次加密结果必须不同。
     */
    fun seal(
        plain: ByteArray,
        passphrase: CharArray,
        salt: ByteArray = randomBytes(SALT_BYTES),
        iv: ByteArray = randomBytes(IV_BYTES),
    ): Sealed {
        require(passphrase.isNotEmpty()) { "口令不能为空" }
        require(salt.size >= 8) { "salt 至少 8 字节" }
        require(iv.size == IV_BYTES) { "GCM iv 必须是 $IV_BYTES 字节" }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return Sealed(cipher.doFinal(plain), salt, iv)
    }

    /** 解密；tag 不匹配统一抛 [WrongPassphraseException]。 */
    fun open(blob: ByteArray, passphrase: CharArray, salt: ByteArray, iv: ByteArray): ByteArray {
        require(passphrase.isNotEmpty()) { "口令不能为空" }
        require(iv.size == IV_BYTES) { "GCM iv 必须是 $IV_BYTES 字节" }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return try {
            cipher.doFinal(blob)
        } catch (e: BadPaddingException) {
            // AEADBadTagException 是 BadPaddingException 的子类：错口令与截断密文都落这里。
            throw WrongPassphraseException(e)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
