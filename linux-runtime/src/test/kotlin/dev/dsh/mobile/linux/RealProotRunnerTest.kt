package dev.dsh.mobile.linux

import java.io.InputStream
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 超时 destroy 时管道另端 close() 会让 read 抛 InterruptedIOException；不能崩线程。 */
class RealProotRunnerTest {

    @Test fun `drain keeps partial output when stream closes mid read`() {
        val stream = object : InputStream() {
            private var served = false
            override fun read(): Int = error("single byte read unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!served) {
                    served = true
                    val bytes = "partial".toByteArray()
                    bytes.copyInto(b, off)
                    return bytes.size
                }
                throw InterruptedIOException("read interrupted by close() on another thread")
            }
        }
        val sink = StringBuilder()
        RealProotRunner.drain(stream, sink)
        assertEquals("partial", sink.toString())
    }

    @Test fun `drain reads until eof`() {
        val sink = StringBuilder()
        RealProotRunner.drain("hello world".byteInputStream(), sink)
        assertEquals("hello world", sink.toString())
    }
}
