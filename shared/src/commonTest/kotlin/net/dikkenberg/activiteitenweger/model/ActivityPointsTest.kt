// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityPointsTest {
    @Test
    fun heavy18MinutesIs1Point8() {
        val item = ActivityRecordPayload(
            startedAt = "2026-09-12T08:00:00Z",
            endedAt = "2026-09-12T08:18:00Z",
            description = "Test",
            category = ActivityCategory.HEAVY,
        )
        assertEquals(1.8, item.pointsRounded())
    }

    @Test
    fun relaxation30MinutesIsMinus1() {
        val item = ActivityRecordPayload(
            startedAt = "2026-09-12T08:00:00Z",
            endedAt = "2026-09-12T08:30:00Z",
            description = "Rust",
            category = ActivityCategory.RELAXATION,
        )
        assertEquals(-1.0, item.pointsRounded())
    }
    @Test
    fun legacyCategoryStringStillDecodes() {
        val json = Json
        val payload = json.decodeFromString<ActivityRecordPayload>(
            """{"schemaVersion":1,"type":"activity","startedAt":"2026-09-12T08:00:00Z","endedAt":"2026-09-12T08:30:00Z","description":"Oud record","category":"licht"}"""
        )

        assertEquals("licht", payload.category.id)
        assertEquals("Licht", payload.category.label)
        assertEquals(1.0, payload.category.pointsPer30Minutes)
        assertEquals(1.0, payload.pointsRounded())
    }

    @Test
    fun customCategoryRoundTripsWithPointSnapshot() {
        val json = Json
        val category = ActivityCategory(
            id = "custom-test",
            label = "Herstel",
            pointsPer30Minutes = -2.5,
        )
        val payload = ActivityRecordPayload(
            startedAt = "2026-09-12T08:00:00Z",
            endedAt = "2026-09-12T08:30:00Z",
            description = "Herstelmoment",
            category = category,
        )

        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("\"category\":\"custom-test\""))

        val decoded = json.decodeFromString<ActivityRecordPayload>(encoded)
        assertEquals(category, decoded.category)
        assertEquals(-2.5, decoded.pointsRounded())
    }

}
