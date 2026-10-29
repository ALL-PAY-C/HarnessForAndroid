package dev.dsh.mobile.core.common

import android.util.Log

object AppLog {
    private const val TAG = "DshMobile"
    @Volatile var verbose: Boolean = true
    private val ring = ArrayDeque<String>(512)

    fun d(msg: String) { if (verbose) Log.d(TAG, msg); push("D $msg") }
    fun i(msg: String) { Log.i(TAG, msg); push("I $msg") }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t); push("W $msg ${t?.message ?: ""}") }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t); push("E $msg ${t?.message ?: ""}") }

    private fun push(line: String) = synchronized(ring) {
        ring.addLast(line)
        while (ring.size > 500) ring.removeFirst()
    }

    fun dump(): String = synchronized(ring) { ring.joinToString("\n") }
}
