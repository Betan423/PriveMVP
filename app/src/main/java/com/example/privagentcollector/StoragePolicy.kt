package com.example.privagentcollector

object StoragePolicy {
    const val ROTATE_BYTES = 5L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 25L * 1024 * 1024
    const val RETAIN_MS = 7L * 24 * 60 * 60 * 1000
    fun expired(modified: Long, now: Long) = now - modified >= RETAIN_MS
    fun rotate(currentBytes: Long, incomingBytes: Long) = currentBytes + incomingBytes > ROTATE_BYTES
}
