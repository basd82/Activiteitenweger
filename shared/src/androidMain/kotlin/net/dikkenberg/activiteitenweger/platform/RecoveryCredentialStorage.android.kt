// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager

actual fun passwordManagerSaveAvailable(): Boolean = true

actual suspend fun saveRecoveryCodeToPasswordManager(
    profileName: String,
    recoveryCode: String,
): Boolean {
    val activity = requireAndroidActivity()
    val manager = CredentialManager.create(activity)
    val request = CreatePasswordRequest(
        id = "Activiteitenweger herstel - " + profileName,
        password = recoveryCode,
    )
    return runCatching {
        manager.createCredential(activity, request)
        true
    }.getOrDefault(false)
}
