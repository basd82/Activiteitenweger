// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

@OptIn(ExperimentalForeignApi::class)
actual fun biometricDisplayName(): String? {
    val context = LAContext()
    return if (context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)) {
        "Face ID / Touch ID"
    } else {
        null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun authenticateBiometric(reason: String): Boolean {
    val context = LAContext()
    if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)) {
        return false
    }
    return suspendCancellableCoroutine { continuation ->
        context.evaluatePolicy(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            localizedReason = reason,
        ) { success, _ ->
            if (continuation.isActive) {
                continuation.resume(success)
            }
        }
        continuation.invokeOnCancellation { context.invalidate() }
    }
}
