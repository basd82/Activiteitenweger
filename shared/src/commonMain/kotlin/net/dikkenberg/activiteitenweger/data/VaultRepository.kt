// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.dikkenberg.activiteitenweger.crypto.CryptoService
import net.dikkenberg.activiteitenweger.crypto.fromBase64Url
import net.dikkenberg.activiteitenweger.crypto.hexLower
import net.dikkenberg.activiteitenweger.crypto.toBase64Url
import net.dikkenberg.activiteitenweger.model.AccessMode
import net.dikkenberg.activiteitenweger.model.ActivityCategory
import net.dikkenberg.activiteitenweger.model.ActivityItem
import net.dikkenberg.activiteitenweger.model.ActivityRecordPayload
import net.dikkenberg.activiteitenweger.model.VaultSession
import net.dikkenberg.activiteitenweger.network.ApiClient
import net.dikkenberg.activiteitenweger.network.CreateVaultRequest
import net.dikkenberg.activiteitenweger.network.UpsertRecordRequest
import net.dikkenberg.activiteitenweger.storage.SessionStore
import kotlin.time.Clock
import kotlin.uuid.Uuid

class VaultRepository(
    private val api: ApiClient,
    private val crypto: CryptoService,
    private val sessions: SessionStore,
    private val json: Json = api.json,
) {
    fun sessions(): List<VaultSession> = sessions.list()

    suspend fun createVault(label: String): VaultSession {
        val vaultId = Uuid.random().toString()
        val deviceId = Uuid.random().toString()
        val signing = crypto.generateEd25519KeyPair()
        val encryption = crypto.generateX25519KeyPair()
        val vaultKey = crypto.randomBytes(32)
        val envelope = crypto.createSelfKeyEnvelope(
            vaultId = vaultId,
            vaultKey = vaultKey,
            signingPrivateKey = signing.privateKey,
            encryptionPrivateKey = encryption.privateKey,
        )

        val response = api.createVault(
            CreateVaultRequest(
                vaultId = vaultId,
                deviceId = deviceId,
                authPublicKey = signing.publicKey.toBase64Url(),
                encryptionPublicKey = encryption.publicKey.toBase64Url(),
                keyEpoch = 1,
                keyEnvelope = envelope.toBase64Url(),
            )
        )

        val session = VaultSession(
            vaultId = response.vaultId,
            deviceId = response.deviceId,
            label = label.ifBlank { "Mijn Activiteitenweger" },
            access = response.access,
            owner = response.owner,
            keyEpoch = response.keyEpoch,
            authPrivateKey = signing.privateKey.toBase64Url(),
            authPublicKey = signing.publicKey.toBase64Url(),
            encryptionPrivateKey = encryption.privateKey.toBase64Url(),
            encryptionPublicKey = encryption.publicKey.toBase64Url(),
            vaultKey = vaultKey.toBase64Url(),
            cursor = 0,
        )
        sessions.save(session)
        return session
    }

    suspend fun loadAllActivities(session: VaultSession): Pair<VaultSession, List<ActivityItem>> {
        var cursor = 0L
        var current = session
        val latest = linkedMapOf<String, ActivityItem>()
        do {
            val response = api.sync(current, cursor, 500)
            current = current.copy(keyEpoch = response.currentKeyEpoch)
            for (record in response.records) {
                if (record.deleted || record.ciphertext == null || record.nonce == null) {
                    latest.remove(record.recordId)
                    continue
                }
                val payload = decryptRecord(current, record.recordId, record.nonce, record.ciphertext)
                latest[record.recordId] = ActivityItem(record.recordId, record.revision, payload)
            }
            cursor = response.nextCursor
        } while (response.hasMore)

        current = current.copy(cursor = cursor)
        sessions.save(current)
        return current to latest.values.sortedByDescending { it.payload.startedAt }
    }

    suspend fun startActivity(
        session: VaultSession,
        description: String,
        category: ActivityCategory,
    ): ActivityItem = createActivity(
        session = session,
        description = description,
        category = category,
        startedAt = Clock.System.now().toString(),
        endedAt = null,
    )

    suspend fun createActivity(
        session: VaultSession,
        description: String,
        category: ActivityCategory,
        startedAt: String,
        endedAt: String?,
    ): ActivityItem {
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        val item = ActivityItem(
            recordId = Uuid.random().toString(),
            revision = 1,
            payload = ActivityRecordPayload(
                startedAt = startedAt,
                endedAt = endedAt,
                description = description.trim().ifBlank { "Activiteit" },
                category = category,
            ),
        )
        put(session, item, deleted = false)
        return item
    }

    suspend fun stopActivity(session: VaultSession, item: ActivityItem): ActivityItem {
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        val updated = item.copy(
            revision = item.revision + 1,
            payload = item.payload.copy(endedAt = Clock.System.now().toString()),
        )
        put(session, updated, deleted = false)
        return updated
    }

    suspend fun updateActivity(session: VaultSession, item: ActivityItem): ActivityItem {
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        val updated = item.copy(revision = item.revision + 1)
        put(session, updated, deleted = false)
        return updated
    }

    suspend fun deleteActivity(session: VaultSession, item: ActivityItem) {
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        put(session, item.copy(revision = item.revision + 1), deleted = true)
    }

    fun renameSession(vaultId: String, label: String): VaultSession {
        val current = sessions.list().first { it.vaultId == vaultId }
        val updated = current.copy(label = label.trim().ifBlank { "Mijn Activiteitenweger" })
        sessions.save(updated)
        return updated
    }

    fun updateCategories(vaultId: String, categories: List<ActivityCategory>): VaultSession {
        val current = sessions.list().first { it.vaultId == vaultId }
        val updated = current.copy(categories = categories)
        sessions.save(updated)
        return updated
    }

    suspend fun deleteVault(session: VaultSession) {
        check(session.owner) { "Alleen de eigenaar kan de volledige vault verwijderen" }
        api.deleteVault(session)
        sessions.remove(session.vaultId)
    }

    private suspend fun put(session: VaultSession, item: ActivityItem, deleted: Boolean) {
        val nonce = if (deleted) null else crypto.randomBytes(24)
        val ciphertext = if (deleted) null else crypto.xChaCha20Poly1305Encrypt(
            key = session.vaultKey.fromBase64Url(),
            nonce24 = nonce!!,
            plaintext = json.encodeToString(item.payload).encodeToByteArray(),
            associatedData = item.recordId.encodeToByteArray(),
        )
        val signature = recordSignature(
            session = session,
            recordId = item.recordId,
            revision = item.revision,
            keyEpoch = session.keyEpoch,
            deleted = deleted,
            nonce = nonce,
            ciphertext = ciphertext,
        )
        api.upsertRecord(
            session,
            UpsertRecordRequest(
                recordId = item.recordId,
                revision = item.revision,
                keyEpoch = session.keyEpoch,
                deleted = deleted,
                ciphertext = ciphertext?.toBase64Url(),
                nonce = nonce?.toBase64Url(),
                recordSignature = signature.toBase64Url(),
            )
        )
    }

    private suspend fun decryptRecord(
        session: VaultSession,
        recordId: String,
        nonce: String,
        ciphertext: String,
    ): ActivityRecordPayload {
        val plaintext = crypto.xChaCha20Poly1305Decrypt(
            key = session.vaultKey.fromBase64Url(),
            nonce24 = nonce.fromBase64Url(),
            ciphertext = ciphertext.fromBase64Url(),
            associatedData = recordId.encodeToByteArray(),
        )
        return json.decodeFromString(plaintext.decodeToString())
    }

    private suspend fun recordSignature(
        session: VaultSession,
        recordId: String,
        revision: Long,
        keyEpoch: Int,
        deleted: Boolean,
        nonce: ByteArray?,
        ciphertext: ByteArray?,
    ): ByteArray {
        val hash = crypto.sha256(ciphertext ?: ByteArray(0)).hexLower()
        val canonical = buildString {
            append("AW-RECORD-V1\n")
            append(session.vaultId).append('\n')
            append(recordId).append('\n')
            append(revision).append('\n')
            append(keyEpoch).append('\n')
            append(if (deleted) '1' else '0').append('\n')
            append(nonce?.toBase64Url().orEmpty()).append('\n')
            append(hash)
        }
        return crypto.signEd25519(
            session.authPrivateKey.fromBase64Url(),
            canonical.encodeToByteArray(),
        )
    }
}
