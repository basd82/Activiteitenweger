// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.storage

data class AppSecuritySettings(
    val enabled: Boolean = false,
    val biometricsEnabled: Boolean = false,
    val lockAfterSeconds: Long = 60,
    val pinSalt: String? = null,
    val pinHash: String? = null,
)

class AppSecurityStore(
    private val secureStore: SecureStore,
) {
    private val enabledKey = "security.enabled"
    private val biometricsKey = "security.biometrics"
    private val timeoutKey = "security.lockAfterSeconds"
    private val saltKey = "security.pinSalt"
    private val hashKey = "security.pinHash"

    fun load(): AppSecuritySettings =
        AppSecuritySettings(
            enabled = secureStore.getString(enabledKey) == "1",
            biometricsEnabled = secureStore.getString(biometricsKey) == "1",
            lockAfterSeconds = secureStore.getString(timeoutKey)?.toLongOrNull()?.coerceAtLeast(0) ?: 60,
            pinSalt = secureStore.getString(saltKey),
            pinHash = secureStore.getString(hashKey),
        )

    fun save(settings: AppSecuritySettings) {
        secureStore.putString(enabledKey, if (settings.enabled) "1" else "0")
        secureStore.putString(biometricsKey, if (settings.biometricsEnabled) "1" else "0")
        secureStore.putString(timeoutKey, settings.lockAfterSeconds.toString())
        settings.pinSalt?.let { secureStore.putString(saltKey, it) } ?: secureStore.remove(saltKey)
        settings.pinHash?.let { secureStore.putString(hashKey, it) } ?: secureStore.remove(hashKey)
    }
}
