package com.chargeguard.ui

import com.chargeguard.data.security.PinResult

fun pinMessage(result: PinResult): String? = when (result) {
    PinResult.Success -> null
    is PinResult.Wrong -> "Incorrect PIN." + if (result.attemptsBeforeLockout in 1..3) " ${result.attemptsBeforeLockout} attempt(s) before a temporary lock." else ""
    is PinResult.LockedOut -> "Too many attempts. Try again in ${(result.remainingMillis + 999) / 1000} s."
    PinResult.NotSet -> "No PIN is set."
    PinResult.InvalidFormat -> "Use 4–8 digits."
}
