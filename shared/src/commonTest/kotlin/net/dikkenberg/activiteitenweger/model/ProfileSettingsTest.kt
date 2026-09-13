// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileSettingsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun profileSettingsRoundTrip() {
        val settings = ProfileSettingsPayload(
            label = "Testprofiel",
            categories = listOf(
                ActivityCategory("rust", "Rust", -2.0),
                ActivityCategory("actief", "Actief", 2.5),
            ),
            activityPresets = listOf(
                ActivityPreset(
                    id = "preset-test",
                    label = "Douchen",
                    categoryId = "actief",
                )
            ),
        )

        val encoded = json.encodeToString(settings)
        val decoded = json.decodeFromString<ProfileSettingsPayload>(encoded)

        assertEquals(settings, decoded)
    }

    @Test
    fun oldVaultSessionGetsSettingsRevisionZero() {
        val encoded =
            """{"vaultId":"11111111-1111-4111-8111-111111111111","deviceId":"22222222-2222-4222-8222-222222222222","label":"Oud","access":"RW","owner":true,"keyEpoch":1,"authPrivateKey":"a","authPublicKey":"b","encryptionPrivateKey":"c","encryptionPublicKey":"d","vaultKey":"e","cursor":0}"""

        val decoded = json.decodeFromString<VaultSession>(encoded)

        assertEquals(0L, decoded.settingsRevision)
        assertEquals(ActivityCategory.defaults, decoded.categories)
        assertEquals(emptyList(), decoded.activityPresets)
    }
}
