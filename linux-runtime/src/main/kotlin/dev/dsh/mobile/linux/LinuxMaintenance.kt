package dev.dsh.mobile.linux

const val DIAGNOSTICS_MAX_BYTES = 64 * 1024

/** Keeps complete UTF-8 lines, dropping the oldest lines first. */
fun appendBounded(current: String, entry: String, maxBytes: Int = DIAGNOSTICS_MAX_BYTES): String {
    require(maxBytes >= 0)
    val lines = (current + entry + "\n").splitToSequence('\n').toList().dropLast(1)
    var used = 0
    val kept = ArrayDeque<String>()
    for (line in lines.asReversed()) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8).size
        if (used + bytes > maxBytes) break
        kept.addFirst(line)
        used += bytes
    }
    return if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n")
}

fun readTail(text: String, maxChars: Int = 4000): String {
    require(maxChars >= 0)
    var start = (text.length - maxChars).coerceAtLeast(0)
    if (start < text.length && text[start].isLowSurrogate()) start++
    return text.substring(start)
}

data class LinuxCleanup(val versions: Int, val bytes: Long)

fun cleanupInactive(environment: LinuxEnvironment): LinuxCleanup {
    val before = environment.usage().bytes
    val count = environment.removeInactive()
    return LinuxCleanup(count, (before - environment.usage().bytes).coerceAtLeast(0))
}
