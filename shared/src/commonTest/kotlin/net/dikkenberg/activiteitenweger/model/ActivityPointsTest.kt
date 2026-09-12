package net.dikkenberg.activiteitenweger.model

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
