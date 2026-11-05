package dev.dsh.mobile.core.platform.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PassphraseCipherTest {

    private val pass = "correct horse battery".toCharArray()

    @Test
    fun `往返解密得到原文`() {
        val plain = "凭证：Bearer abc.def.ghi 与中文标题".toByteArray()
        val sealed = PassphraseCipher.seal(plain, pass)
        val opened = PassphraseCipher.open(sealed.blob, pass, sealed.salt, sealed.iv)
        assertArrayEquals(plain, opened)
        assertNotEquals(plain.size, sealed.blob.size) // 多了 16 字节 GCM tag
        assertTrue(sealed.blob.size > plain.size)
    }

    @Test
    fun `错口令抛 WrongPassphraseException`() {
        val sealed = PassphraseCipher.seal("secret".toByteArray(), pass)
        try {
            PassphraseCipher.open(sealed.blob, "wrong horse battery".toCharArray(), sealed.salt, sealed.iv)
            fail("错口令不该解出明文")
        } catch (e: WrongPassphraseException) {
            assertTrue(e.message!!.contains("口令错误"))
        }
    }

    @Test
    fun `空口令被拒绝`() {
        try {
            PassphraseCipher.seal("secret".toByteArray(), CharArray(0))
            fail("空口令不该通过")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("口令不能为空"))
        }
        val sealed = PassphraseCipher.seal("secret".toByteArray(), pass)
        try {
            PassphraseCipher.open(sealed.blob, CharArray(0), sealed.salt, sealed.iv)
            fail("空口令不该解密")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("口令不能为空"))
        }
    }

    @Test
    fun `两次加密的 salt 与 iv 都不同`() {
        val plain = "same plaintext".toByteArray()
        val a = PassphraseCipher.seal(plain, pass)
        val b = PassphraseCipher.seal(plain, pass)
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.blob.contentEquals(b.blob))
        assertTrue(a.salt.size >= 8)
        assertTrue(a.iv.size == PassphraseCipher.IV_BYTES)
    }
}
