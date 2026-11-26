package com.chargeguard.data.security

import com.chargeguard.domain.repository.Clock
import com.chargeguard.domain.repository.OwnerPinVerifier
import java.security.MessageDigest
import java.util.Base64

/** Minimal key-value storage so the PIN logic can run on the JVM in unit tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    fun edit(block: MutableMap<String, Any?>.() -> Unit)
}

/** Keyed MAC over the salted PIN. On device the key lives in Android Keystore (non-exportable). */
fun interface PinMac {
    fun mac(data: ByteArray): ByteArray
}

sealed interface PinResult {
    data object Success : PinResult
    data class Wrong(val attemptsBeforeLockout: Int) : PinResult
    data class LockedOut(val remainingMillis: Long) : PinResult
    data object NotSet : PinResult
    data object InvalidFormat : PinResult
}

/**
 * Owner PIN verification with persistent rate limiting.
 *
 * - The PIN is never stored. Only `salt` and `HMAC(keystoreKey, salt || pin)` are stored, so the
 *   stored values are useless without the device-bound Keystore key (no offline brute force).
 * - After [FREE_ATTEMPTS] consecutive failures, each further failure locks entry for an
 *   exponentially growing period (30 s, 60 s, ... capped at 15 min). Counters survive restarts.
 * - Setting the clock backwards does not shorten a lockout.
 */
class PinVerifier(
    private val store: KeyValueStore,
    private val pinMac: PinMac,
    private val clock: Clock,
    private val randomBytes: (Int) -> ByteArray,
) : OwnerPinVerifier {

    override fun isPinSet(): Boolean = store.getString(KEY_VERIFIER) != null

    fun lockoutRemainingMillis(): Long {
        val until = store.getLong(KEY_LOCKED_UNTIL, 0L)
        if (until <= 0L) return 0L
        val now = effectiveNow()
        return (until - now).coerceAtLeast(0L)
    }

    fun verify(pin: String): PinResult {
        if (!isPinSet()) return PinResult.NotSet
        val remaining = lockoutRemainingMillis()
        if (remaining > 0) return PinResult.LockedOut(remaining)
        if (!isValidFormat(pin)) return registerFailure()

        val salt = decode(store.getString(KEY_SALT)) ?: return PinResult.NotSet
        val expected = decode(store.getString(KEY_VERIFIER)) ?: return PinResult.NotSet
        val actual = pinMac.mac(salt + pin.toByteArray(Charsets.UTF_8))
        return if (MessageDigest.isEqual(expected, actual)) {
            store.edit {
                this[KEY_FAILURES] = 0L
                this[KEY_LOCKED_UNTIL] = 0L
            }
            PinResult.Success
        } else {
            registerFailure()
        }
    }

    /** Sets a new PIN. If a PIN already exists, [currentPin] must verify first. */
    fun setPin(newPin: String, currentPin: String?): PinResult {
        if (!isValidFormat(newPin)) return PinResult.InvalidFormat
        if (isPinSet()) {
            val check = verify(currentPin ?: return PinResult.Wrong(attemptsLeft()))
            if (check != PinResult.Success) return check
        }
        val salt = randomBytes(SALT_BYTES)
        val verifier = pinMac.mac(salt + newPin.toByteArray(Charsets.UTF_8))
        store.edit {
            this[KEY_SALT] = encode(salt)
            this[KEY_VERIFIER] = encode(verifier)
            this[KEY_FAILURES] = 0L
            this[KEY_LOCKED_UNTIL] = 0L
        }
        return PinResult.Success
    }

    fun removePin(currentPin: String): PinResult {
        val check = verify(currentPin)
        if (check != PinResult.Success) return check
        store.edit {
            this[KEY_SALT] = null
            this[KEY_VERIFIER] = null
            this[KEY_FAILURES] = 0L
            this[KEY_LOCKED_UNTIL] = 0L
        }
        return PinResult.Success
    }

    private fun registerFailure(): PinResult {
        val failures = store.getLong(KEY_FAILURES, 0L) + 1
        val now = effectiveNow()
        val lockout = lockoutFor(failures)
        store.edit {
            this[KEY_FAILURES] = failures
            this[KEY_LAST_FAILURE] = now
            this[KEY_LOCKED_UNTIL] = if (lockout > 0) now + lockout else 0L
        }
        return if (lockout > 0) PinResult.LockedOut(lockout) else PinResult.Wrong(attemptsLeft())
    }

    private fun attemptsLeft(): Int =
        (FREE_ATTEMPTS - store.getLong(KEY_FAILURES, 0L).toInt()).coerceAtLeast(0)

    /** Never earlier than the last recorded failure, so turning the clock back doesn't help. */
    private fun effectiveNow(): Long = maxOf(clock.now(), store.getLong(KEY_LAST_FAILURE, 0L))

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        const val FREE_ATTEMPTS = 5
        const val BASE_LOCKOUT_MILLIS = 30_000L
        const val MAX_LOCKOUT_MILLIS = 15 * 60_000L
        private const val SALT_BYTES = 16

        const val KEY_SALT = "pin_salt"
        const val KEY_VERIFIER = "pin_verifier"
        const val KEY_FAILURES = "pin_failures"
        const val KEY_LOCKED_UNTIL = "pin_locked_until"
        const val KEY_LAST_FAILURE = "pin_last_failure"

        fun isValidFormat(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

        fun lockoutFor(failures: Long): Long {
            if (failures < FREE_ATTEMPTS) return 0L
            val exponent = (failures - FREE_ATTEMPTS).coerceAtMost(10).toInt()
            return (BASE_LOCKOUT_MILLIS shl exponent).coerceAtMost(MAX_LOCKOUT_MILLIS)
        }

        private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        private fun decode(value: String?): ByteArray? = value?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    }
}
