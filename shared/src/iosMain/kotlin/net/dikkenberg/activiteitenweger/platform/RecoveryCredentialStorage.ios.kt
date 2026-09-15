// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.platform

actual fun passwordManagerSaveAvailable(): Boolean = false

actual suspend fun saveRecoveryCodeToPasswordManager(
    profileName: String,
    recoveryCode: String,
): Boolean = false
