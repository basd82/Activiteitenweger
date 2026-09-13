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
import net.dikkenberg.activiteitenweger.model.ActivityPreset
import net.dikkenberg.activiteitenweger.model.ActivityRecordPayload
import net.dikkenberg.activiteitenweger.model.ProfileSettingsPayload
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
        return updateProfileSettings(session)
    }

    suspend fun loadAllActivities(session: VaultSession): Pair<VaultSession, List<ActivityItem>> =
        syncActivities(
            session = session,
            currentActivities = emptyList(),
            fullRefresh = true,
        )

    suspend fun syncActivities(
        session: VaultSession,
        currentActivities: List<ActivityItem>,
        fullRefresh: Boolean = false,
    ): Pair<VaultSession, List<ActivityItem>> {
        var cursor = if (fullRefresh) 0L else session.cursor
        var current = if (fullRefresh) session.copy(cursor = 0) else session
        var settingsSeen = false
        val latest = linkedMapOf<String, ActivityItem>().apply {
            if (!fullRefresh) {
                currentActivities.forEach { put(it.recordId, it) }
            }
        }

        do {
            val response = api.sync(current, cursor, 500)
            current = current.copy(keyEpoch = response.currentKeyEpoch)

            for (record in response.records) {
                if (record.recordId == settingsRecordId(current)) {
                    settingsSeen = true

                    if (!record.deleted && record.ciphertext != null && record.nonce != null) {
                        val settings = decryptProfileSettings(
                            session = current,
                            recordId = record.recordId,
                            nonce = record.nonce,
                            ciphertext = record.ciphertext,
                        )
                        val categories = settings.categories.ifEmpty { ActivityCategory.defaults }
                        val categoryIds = categories.mapTo(mutableSetOf()) { it.id }
                        current = current.copy(
                            label = settings.label.trim().ifBlank { current.label },
                            categories = categories,
                            activityPresets = settings.activityPresets.filter {
                                it.categoryId in categoryIds
                            },
                            settingsRevision = record.revision,
                        )
                    } else {
                        current = current.copy(settingsRevision = record.revision)
                    }
                    continue
                }

                if (record.deleted || record.ciphertext == null || record.nonce == null) {
                    latest.remove(record.recordId)
                    continue
                }

                val payload = decryptActivity(
                    session = current,
                    recordId = record.recordId,
                    nonce = record.nonce,
                    ciphertext = record.ciphertext,
                )
                latest[record.recordId] = ActivityItem(
                    recordId = record.recordId,
                    revision = record.revision,
                    payload = payload,
                )
            }

            cursor = response.nextCursor
        } while (response.hasMore)

        current = current.copy(cursor = cursor)

        // A full refresh is used after app start/profile switch because activities
        // are currently kept in memory rather than in a persistent local database.
        // Existing vaults made before synced settings were introduced are migrated
        // by publishing their current local settings once.
        if (
            fullRefresh &&
            !settingsSeen &&
            current.settingsRevision == 0L &&
            current.access == AccessMode.RW
        ) {
            current = updateProfileSettings(current)
        } else {
            sessions.save(current)
        }

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

    suspend fun updateProfileSettings(
        session: VaultSession,
        label: String = session.label,
        categories: List<ActivityCategory> = session.categories,
        activityPresets: List<ActivityPreset> = session.activityPresets,
    ): VaultSession {
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        require(categories.isNotEmpty()) { "Er moet minimaal één categorie zijn" }

        val categoryIds = categories.mapTo(mutableSetOf()) { it.id }
        check(activityPresets.all { it.categoryId in categoryIds }) {
            "Een standaardactiviteit verwijst naar een onbekende categorie"
        }

        val payload = ProfileSettingsPayload(
            label = label.trim().ifBlank { "Mijn Activiteitenweger" },
            categories = categories,
            activityPresets = activityPresets,
        )
        val revision = session.settingsRevision + 1

        putEncryptedRecord(
            session = session,
            recordId = settingsRecordId(session),
            revision = revision,
            plaintext = json.encodeToString(payload).encodeToByteArray(),
            deleted = false,
        )

        val updated = session.copy(
            label = payload.label,
            categories = payload.categories,
            activityPresets = payload.activityPresets,
            settingsRevision = revision,
        )
        sessions.save(updated)
        return updated
    }

    suspend fun deleteVault(session: VaultSession) {
        check(session.owner) { "Alleen de eigenaar kan de volledige vault verwijderen" }
        api.deleteVault(session)
        sessions.remove(session.vaultId)
    }

    private suspend fun put(session: VaultSession, item: ActivityItem, deleted: Boolean) {
        putEncryptedRecord(
            session = session,
            recordId = item.recordId,
            revision = item.revision,
            plaintext = if (deleted) null else json.encodeToString(item.payload).encodeToByteArray(),
            deleted = deleted,
        )
    }

    private suspend fun putEncryptedRecord(
        session: VaultSession,
        recordId: String,
        revision: Long,
        plaintext: ByteArray?,
        deleted: Boolean,
    ) {
        val nonce = if (deleted) null else crypto.randomBytes(24)
        val ciphertext = if (deleted) {
            null
        } else {
            crypto.xChaCha20Poly1305Encrypt(
                key = session.vaultKey.fromBase64Url(),
                nonce24 = requireNotNull(nonce),
                plaintext = requireNotNull(plaintext),
                associatedData = recordId.encodeToByteArray(),
            )
        }
        val signature = recordSignature(
            session = session,
            recordId = recordId,
            revision = revision,
            keyEpoch = session.keyEpoch,
            deleted = deleted,
            nonce = nonce,
            ciphertext = ciphertext,
        )
        api.upsertRecord(
            session,
            UpsertRecordRequest(
                recordId = recordId,
                revision = revision,
                keyEpoch = session.keyEpoch,
                deleted = deleted,
                ciphertext = ciphertext?.toBase64Url(),
                nonce = nonce?.toBase64Url(),
                recordSignature = signature.toBase64Url(),
            )
        )
    }

    private suspend fun decryptActivity(
        session: VaultSession,
        recordId: String,
        nonce: String,
        ciphertext: String,
    ): ActivityRecordPayload =
        json.decodeFromString(
            decryptPlaintext(session, recordId, nonce, ciphertext).decodeToString()
        )

    private suspend fun decryptProfileSettings(
        session: VaultSession,
        recordId: String,
        nonce: String,
        ciphertext: String,
    ): ProfileSettingsPayload =
        json.decodeFromString(
            decryptPlaintext(session, recordId, nonce, ciphertext).decodeToString()
        )

    private suspend fun decryptPlaintext(
        session: VaultSession,
        recordId: String,
        nonce: String,
        ciphertext: String,
    ): ByteArray =
        crypto.xChaCha20Poly1305Decrypt(
            key = session.vaultKey.fromBase64Url(),
            nonce24 = nonce.fromBase64Url(),
            ciphertext = ciphertext.fromBase64Url(),
            associatedData = recordId.encodeToByteArray(),
        )

    private fun settingsRecordId(session: VaultSession): String = session.vaultId

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
