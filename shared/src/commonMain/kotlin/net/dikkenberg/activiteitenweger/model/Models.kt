package net.dikkenberg.activiteitenweger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
enum class ActivityCategory(val label: String, val pointsPer30Minutes: Double) {
    @SerialName("ontspanning") RELAXATION("Ontspanning", -1.0),
    @SerialName("licht") LIGHT("Licht", 1.0),
    @SerialName("gemiddeld") MEDIUM("Gemiddeld", 2.0),
    @SerialName("zwaar") HEAVY("Zwaar", 3.0),
}

@Serializable
enum class AccessMode { R, RW }

@Serializable
data class ActivityRecordPayload(
    val schemaVersion: Int = 1,
    val type: String = "activity",
    val startedAt: String,
    val endedAt: String? = null,
    val description: String,
    val category: ActivityCategory,
) {
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
data class ActivityItem(
    val recordId: String,
    val revision: Long,
    val payload: ActivityRecordPayload,
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
)
