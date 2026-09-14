// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class ActivityCategory(
    val id: String,
    val label: String,
    val pointsPer30Minutes: Double,
) {
    companion object {
        val RELAXATION = ActivityCategory("ontspanning", "Ontspanning", -1.0)
        val LIGHT = ActivityCategory("licht", "Licht", 1.0)
        val MEDIUM = ActivityCategory("gemiddeld", "Gemiddeld", 2.0)
        val HEAVY = ActivityCategory("zwaar", "Zwaar", 3.0)

        val defaults: List<ActivityCategory>
            get() = listOf(RELAXATION, LIGHT, MEDIUM, HEAVY)

        fun builtInById(id: String): ActivityCategory? =
            defaults.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

@Serializable
data class ActivityPreset(
    val id: String,
    val label: String,
    val categoryId: String,
)

@Serializable
data class ProfileSettingsPayload(
    val schemaVersion: Int = 1,
    val type: String = "profile_settings",
    val label: String,
    val categories: List<ActivityCategory> = ActivityCategory.defaults,
    val activityPresets: List<ActivityPreset> = emptyList(),
)

@Serializable
enum class AccessMode { R, RW }

@Serializable
data class ActivityRecordPayload(
    val schemaVersion: Int = 2,
    val type: String = "activity",
    val startedAt: String,
    val endedAt: String? = null,
    val description: String,
    @SerialName("category")
    val categoryId: String,
    val categoryLabel: String? = null,
    val categoryPointsPer30Minutes: Double? = null,
) {
    constructor(
        schemaVersion: Int = 2,
        type: String = "activity",
        startedAt: String,
        endedAt: String? = null,
        description: String,
        category: ActivityCategory,
    ) : this(
        schemaVersion = schemaVersion,
        type = type,
        startedAt = startedAt,
        endedAt = endedAt,
        description = description,
        categoryId = category.id,
        categoryLabel = category.label,
        categoryPointsPer30Minutes = category.pointsPer30Minutes,
    )

    val category: ActivityCategory
        get() {
            val builtIn = ActivityCategory.builtInById(categoryId)
            return ActivityCategory(
                id = categoryId,
                label = categoryLabel ?: builtIn?.label ?: categoryId,
                pointsPer30Minutes = categoryPointsPer30Minutes ?: builtIn?.pointsPer30Minutes ?: 0.0,
            )
        }

    fun withCategory(category: ActivityCategory): ActivityRecordPayload =
        copy(
            schemaVersion = maxOf(schemaVersion, 2),
            categoryId = category.id,
            categoryLabel = category.label,
            categoryPointsPer30Minutes = category.pointsPer30Minutes,
        )

    fun durationSeconds(now: Instant = Clock.System.now()): Long {
        val start = Instant.parse(startedAt)
        val end = endedAt?.let(Instant::parse) ?: now
        return (end - start).inWholeSeconds.coerceAtLeast(0)
    }

    fun durationMinutes(now: Instant = Clock.System.now()): Double =
        durationSeconds(now) / 60.0

    fun points(now: Instant = Clock.System.now()): Double =
        (durationMinutes(now) / 30.0) * category.pointsPer30Minutes

    fun pointsRounded(now: Instant = Clock.System.now()): Double =
        round(points(now) * 10.0) / 10.0

    fun localDate(): String =
        Instant.parse(startedAt)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toString()
}

@Serializable
enum class SyncStatus {
    SYNCED,
    PENDING,
    CONFLICT,
}

@Serializable
data class ActivityItem(
    val recordId: String,
    val revision: Long,
    val payload: ActivityRecordPayload,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
)

@Serializable
data class VaultSession(
    val vaultId: String,
    val deviceId: String,
    val label: String,
    val access: AccessMode,
    val owner: Boolean,
    val keyEpoch: Int,
    val authPrivateKey: String,
    val authPublicKey: String,
    val encryptionPrivateKey: String,
    val encryptionPublicKey: String,
    val vaultKey: String,
    val cursor: Long = 0,
    val categories: List<ActivityCategory> = ActivityCategory.defaults,
    val activityPresets: List<ActivityPreset> = emptyList(),
    val settingsRevision: Long = 0,
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val access: AccessMode,
    val owner: Boolean,
    val status: String,
    val createdAt: String,
    val lastSeenAt: String? = null,
    val revokedAt: String? = null,
    val name: String? = null,
)


data class PairingInvitation(
    val inviteId: String,
    val code: String,
    val qrPayload: String,
    val access: AccessMode,
    val expiresInSeconds: Int,
)

data class ActivityConflictSnapshot(
    val recordId: String,
    val localRevision: Long,
    val remoteRevision: Long,
    val localPayload: ActivityRecordPayload?,
    val remotePayload: ActivityRecordPayload?,
    val localDeleted: Boolean,
    val remoteDeleted: Boolean,
)
