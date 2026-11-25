package minty.emanata.util

import java.security.MessageDigest

/** Cache-file names from keys. */
object Hashes {
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Lowercase hex of the first [bytes] bytes of [value]'s SHA-256 (UTF-8). Only a
     * collision-resistant file name is needed, but SHA-256 keeps clear of weak hashes
     * (Sonar S4790); the hex is built without a locale-sensitive formatter.
     */
    fun sha256Hex(value: String, bytes: Int = 32): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val n = bytes.coerceIn(1, digest.size)
        val hex = CharArray(n * 2)
        for (i in 0 until n) {
            val b = digest[i].toInt() and 0xFF
            hex[i * 2] = HEX_DIGITS[b ushr 4]
            hex[i * 2 + 1] = HEX_DIGITS[b and 0x0F]
        }
        return String(hex)
    }
}
