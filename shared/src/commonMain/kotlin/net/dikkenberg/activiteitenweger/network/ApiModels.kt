// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.network

import kotlinx.serialization.Serializable
import net.dikkenberg.activiteitenweger.model.AccessMode

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    val apiVersion: Int,
    val serverVersion: String? = null,
)

@Serializable
data class CreateVaultRequest(
    val vaultId: String,
    val deviceId: String,
    val authPublicKey: String,
    val encryptionPublicKey: String,
    val keyEpoch: Int = 1,
    val keyEnvelope: String,
    val keyEnvelopeNonce: String? = null,
)

@Serializable
data class CreateVaultResponse(
    val status: String,
    val vaultId: String,
    val deviceId: String,
    val access: AccessMode,
    val owner: Boolean,
    val keyEpoch: Int,
)

@Serializable
data class MeResponse(
    val deviceId: String,
    val vaultId: String,
    val access: AccessMode,
    val owner: Boolean,
    val currentKeyEpoch: Int,
)

@Serializable
data class DevicesResponse(val devices: List<DeviceResponse>)

@Serializable
data class DeviceResponse(
    val deviceId: String,
    val access: AccessMode,
    val owner: Boolean,
    val status: String,
    val createdAt: String,
    val lastSeenAt: String? = null,
    val revokedAt: String? = null,
)


@Serializable
data class CreatePairingInviteRequest(
    val inviteId: String,
    val access: AccessMode,
    val pairingSecretHash: String,
    val keyPackageCiphertext: String,
    val keyPackageNonce: String,
    val expiresInSeconds: Int = 600,
)

@Serializable
data class CreatePairingInviteResponse(
    val status: String,
    val inviteId: String,
    val access: AccessMode,
    val keyEpoch: Int,
    val expiresInSeconds: Int,
)

@Serializable
data class ClaimPairingRequest(
    val pairingSecret: String,
    val deviceId: String,
    val authPublicKey: String,
    val encryptionPublicKey: String,
)

@Serializable
data class ClaimPairingResponse(
    val status: String,
    val inviteId: String,
    val vaultId: String,
    val deviceId: String,
    val access: AccessMode,
    val owner: Boolean,
    val keyEpoch: Int,
    val keyPackageCiphertext: String,
    val keyPackageNonce: String,
)

@Serializable
data class RevokeResponse(
    val status: String,
    val deviceId: String? = null,
)

@Serializable
data class UpsertRecordRequest(
    val recordId: String,
    val revision: Long,
    val keyEpoch: Int,
    val deleted: Boolean,
    val ciphertext: String? = null,
    val nonce: String? = null,
    val recordSignature: String,
)

@Serializable
data class UpsertRecordResponse(
    val status: String,
    val recordId: String,
    val revision: Long,
    val deleted: Boolean,
    val eventId: Long,
)

@Serializable
data class RemoteRecord(
    val recordId: String,
    val keyEpoch: Int,
    val revision: Long,
    val cryptoVersion: Int,
    val ciphertext: String? = null,
    val nonce: String? = null,
    val writerDeviceId: String,
    val recordSignature: String,
    val deleted: Boolean,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class SyncResponse(
    val records: List<RemoteRecord>,
    val nextCursor: Long,
    val hasMore: Boolean,
    val currentKeyEpoch: Int,
)

@Serializable
data class DeleteVaultRequest(val confirm: String = "DELETE")

@Serializable
data class DeleteVaultResponse(val status: String)

@Serializable
data class ErrorResponse(
    val error: String? = null,
    val recordId: String? = null,
    val currentRevision: Long? = null,
    val expectedRevision: Long? = null,
    val currentDeleted: Boolean? = null,
    val currentUpdatedAt: String? = null,
    val currentKeyEpoch: Int? = null,
)
