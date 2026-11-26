package com.chargeguard.data.security

import com.chargeguard.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PinVerifierTest {

    private class MemoryStore : KeyValueStore {
        val map = mutableMapOf<String, Any?>()
        override fun getString(key: String) = map[key] as? String
        override fun getLong(key: String, default: Long) = (map[key] as? Long) ?: default
        override fun edit(block: MutableMap<String, Any?>.() -> Unit) {
            val changes = mutableMapOf<String, Any?>().apply(block)
            changes.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }

    // Stand-in for the Keystore key: a fixed software HMAC key.
    private val mac = PinMac { data ->
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")) }.doFinal(data)
    }
    private val store = MemoryStore()
    private val clock = FakeClock(1_000_000L)
    private var counter = 0
    private val verifier = PinVerifier(store, mac, clock) { n -> ByteArray(n) { (counter++).toByte() } }

    @Test fun noPin_initially() {
        assertFalse(verifier.isPinSet())
        assertEquals(PinResult.NotSet, verifier.verify("1234"))
    }

    @Test fun setAndVerify() {
        assertEquals(PinResult.Success, verifier.setPin("2580", null))
        assertTrue(verifier.isPinSet())
        assertEquals(PinResult.Success, verifier.verify("2580"))
        assertTrue(verifier.verify("0000") is PinResult.Wrong)
    }

    @Test fun pin_isNeverStoredInPlainText() {
        verifier.setPin("135790", null)
        assertFalse(store.map.values.any { it.toString().contains("135790") })
        assertNull(store.map["pin"])
    }

    @Test fun invalidFormats_rejected() {
        assertEquals(PinResult.InvalidFormat, verifier.setPin("12", null))
        assertEquals(PinResult.InvalidFormat, verifier.setPin("123456789", null))
        assertEquals(PinResult.InvalidFormat, verifier.setPin("12a4", null))
    }

    @Test fun changePin_requiresCurrent() {
        verifier.setPin("1111", null)
        assertTrue(verifier.setPin("2222", "9999") is PinResult.Wrong)
        assertEquals(PinResult.Success, verifier.setPin("2222", "1111"))
        assertEquals(PinResult.Success, verifier.verify("2222"))
    }

    @Test fun lockout_afterFiveFailures_thenExpires() {
        verifier.setPin("4321", null)
        repeat(4) { assertTrue(verifier.verify("0000") is PinResult.Wrong) }
        val fifth = verifier.verify("0000")
        assertTrue(fifth is PinResult.LockedOut)
        // Even the correct PIN is refused while locked.
        assertTrue(verifier.verify("4321") is PinResult.LockedOut)
        clock.time += PinVerifier.BASE_LOCKOUT_MILLIS + 1
        assertEquals(PinResult.Success, verifier.verify("4321"))
        assertEquals(0L, verifier.lockoutRemainingMillis())
    }

    @Test fun lockout_growsExponentially_andIsCapped() {
        assertEquals(0L, PinVerifier.lockoutFor(4))
        assertEquals(30_000L, PinVerifier.lockoutFor(5))
        assertEquals(60_000L, PinVerifier.lockoutFor(6))
        assertEquals(120_000L, PinVerifier.lockoutFor(7))
        assertEquals(PinVerifier.MAX_LOCKOUT_MILLIS, PinVerifier.lockoutFor(50))
    }

    @Test fun movingClockBackwards_doesNotShortenLockout() {
        verifier.setPin("4321", null)
        repeat(5) { verifier.verify("0000") }
        clock.time -= 3_600_000L
        assertTrue(verifier.verify("4321") is PinResult.LockedOut)
    }

    @Test fun lockout_survivesNewVerifierInstance() {
        verifier.setPin("4321", null)
        repeat(5) { verifier.verify("0000") }
        val restarted = PinVerifier(store, mac, clock) { ByteArray(it) }
        assertTrue(restarted.verify("4321") is PinResult.LockedOut)
    }

    @Test fun removePin_requiresCorrectPin() {
        verifier.setPin("1234", null)
        assertTrue(verifier.removePin("0000") is PinResult.Wrong)
        assertTrue(verifier.isPinSet())
        assertEquals(PinResult.Success, verifier.removePin("1234"))
        assertFalse(verifier.isPinSet())
    }
}
