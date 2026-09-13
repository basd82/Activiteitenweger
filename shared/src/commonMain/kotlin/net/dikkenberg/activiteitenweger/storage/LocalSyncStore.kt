// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.storage

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedEncryptedRecord(
    val recordId: String,
    val revision: Long,
    val keyEpoch: Int,
    val deleted: Boolean,
    val ciphertext: String? = null,
    val nonce: String? = null,
    val writerDeviceId: String? = null,
    val recordSignature: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
)

@Serializable
enum class PendingMutationState {
    PENDING,
    CONFLICT,
}

@Serializable
data class PendingRecordMutation(
    val recordId: String,
    val baseRevision: Long,
    val revision: Long,
    val keyEpoch: Int,
    val deleted: Boolean,
    val ciphertext: String? = null,
    val nonce: String? = null,
    val recordSignature: String,
    val localChangedAt: String,
    val state: PendingMutationState = PendingMutationState.PENDING,
)

@Serializable
data class LocalVaultSyncState(
    val schemaVersion: Int = 1,
    val cursor: Long = 0,
    val records: List<CachedEncryptedRecord> = emptyList(),
    val pending: List<PendingRecordMutation> = emptyList(),
)

class LocalSyncStore(
    private val json: Json,
) {
    private val directory: PlatformFile
        get() = PlatformFile(FileKit.filesDir, "sync")

    private fun file(vaultId: String): PlatformFile =
        PlatformFile(directory, "$vaultId.json")

    suspend fun load(vaultId: String): LocalVaultSyncState {
        val file = file(vaultId)
        if (!file.exists()) return LocalVaultSyncState()

        return runCatching {
            json.decodeFromString<LocalVaultSyncState>(file.readString())
        }.getOrElse {
            LocalVaultSyncState()
        }
    }

    suspend fun save(vaultId: String, state: LocalVaultSyncState) {
        if (!directory.exists()) {
            directory.createDirectories()
        }
        file(vaultId).writeString(json.encodeToString(state))
    }

    suspend fun remove(vaultId: String) {
        file(vaultId).delete(mustExist = false)
    }
}
