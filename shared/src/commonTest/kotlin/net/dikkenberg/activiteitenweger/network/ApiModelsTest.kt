// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ApiModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun healthResponseReadsServerVersion() {
        val response = json.decodeFromString<HealthResponse>(
            """{"status":"ok","database":"ok","apiVersion":1,"serverVersion":"1.1.0"}"""
        )

        assertEquals("ok", response.status)
        assertEquals(1, response.apiVersion)
        assertEquals("1.1.0", response.serverVersion)
    }

    @Test
    fun revisionConflictMetadataIsDecoded() {
        val response = json.decodeFromString<ErrorResponse>(
            """{
                "error":"revision_conflict",
                "recordId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "currentRevision":5,
                "expectedRevision":6,
                "currentDeleted":false,
                "currentUpdatedAt":"2026-09-13T14:00:00.000000Z"
            }"""
        )

        assertEquals("revision_conflict", response.error)
        assertEquals(5, response.currentRevision)
        assertEquals(6, response.expectedRevision)
        assertFalse(response.currentDeleted ?: true)
        assertEquals("2026-09-13T14:00:00.000000Z", response.currentUpdatedAt)
    }
}
