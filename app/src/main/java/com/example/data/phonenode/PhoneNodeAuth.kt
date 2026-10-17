package com.example.data.phonenode

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing and token checks for the phone-as-a-node server. Same rules as the PC agent:
 * a one-time code (8 characters, 10 minutes, shown only on this phone's screen), five wrong
 * guesses lock pairing for a minute, tokens are random and only their SHA-256 is stored.
 * No Android classes here, so it is fully unit-testable.
 */
class PhoneNodeAuth(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Store {
        fun load(): List<Device>
        fun save(devices: List<Device>)
    }

    data class Device(val label: String, val hash: String, val createdAt: Long)
    class IssuedCode(val code: String, val expiresAt: Long)

    sealed interface Pairing {
        data class Ok(val token: String) : Pairing
        data object BadCode : Pairing
        data object Locked : Pairing
    }

    private val lock = Any()
    private val random = SecureRandom()
    private val devices: MutableList<Device> = store.load().toMutableList()
    private var code = ""
    private var codeExpires = 0L
    private var bad = 0
    private var lockedUntil = 0L

    /** A fresh code, replacing any earlier one. Only the phone's own UI calls this; there is no network path to it. */
    fun newCode(): IssuedCode = synchronized(lock) {
        code = (1..CODE_LEN).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        codeExpires = clock() + CODE_TTL_MS
        IssuedCode(display(code), codeExpires)
    }

    /** The code currently on offer, or null if none or it has expired. */
    fun currentCode(): IssuedCode? = synchronized(lock) {
        if (code.isEmpty() || clock() >= codeExpires) null else IssuedCode(display(code), codeExpires)
    }

    private fun display(c: String) = c.substring(0, 4) + "-" + c.substring(4)

    private fun normalize(s: String) = s.uppercase().replace("-", "").replace(" ", "").trim()

    fun pair(given: String, label: String): Pairing = synchronized(lock) {
        val now = clock()
        if (now < lockedUntil) return Pairing.Locked
        val valid = code.isNotEmpty() && now < codeExpires &&
            MessageDigest.isEqual(normalize(given).toByteArray(), code.toByteArray())
        if (!valid) {
            bad++
            if (bad >= MAX_BAD_CODES) {
                bad = 0
                lockedUntil = now + LOCKOUT_MS
                return Pairing.Locked
            }
            return Pairing.BadCode
        }
        val token = ByteArray(32).also { random.nextBytes(it) }.toHex()
        val device = Device(cleanLabel(label), sha256Hex(token), now)
        devices.add(device)
        try { store.save(devices) } catch (e: Exception) { devices.remove(device); throw e }
        code = ""
        bad = 0
        Pairing.Ok(token)
    }

    /** The label a token was paired under, or null if the token is unknown. */
    fun label(token: String): String? {
        if (token.isEmpty()) return null
        val want = sha256Hex(token).toByteArray()
        synchronized(lock) {
            var found: String? = null
            for (d in devices) if (MessageDigest.isEqual(d.hash.toByteArray(), want)) found = d.label
            return found
        }
    }

    fun verify(token: String): Boolean = label(token) != null

    fun devices(): List<Device> = synchronized(lock) { devices.toList() }

    /** Removes every device with this label; returns how many. */
    fun revoke(label: String): Int = synchronized(lock) {
        val before = devices.size
        devices.removeAll { it.label == label }
        val removed = before - devices.size
        if (removed > 0) store.save(devices)
        removed
    }

    private fun cleanLabel(l: String): String = l.trim().ifEmpty { "device" }.take(64)

    companion object {
        const val CODE_TTL_MS = 10 * 60_000L
        const val LOCKOUT_MS = 60_000L
        const val MAX_BAD_CODES = 5
        private const val CODE_LEN = 8
        // No 0/O/1/I/L: a code read off a screen cannot be mistyped.
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
