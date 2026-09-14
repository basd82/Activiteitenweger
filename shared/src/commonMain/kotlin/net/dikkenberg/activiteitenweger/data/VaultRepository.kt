// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.dikkenberg.activiteitenweger.crypto.CryptoService
import net.dikkenberg.activiteitenweger.crypto.fromBase64Url
import net.dikkenberg.activiteitenweger.crypto.fromHexFlexible
import net.dikkenberg.activiteitenweger.crypto.groupedPairingCode
import net.dikkenberg.activiteitenweger.crypto.hexLower
import net.dikkenberg.activiteitenweger.crypto.hexUpper
import net.dikkenberg.activiteitenweger.crypto.toBase64Url
import net.dikkenberg.activiteitenweger.model.AccessMode
import net.dikkenberg.activiteitenweger.model.ActivityCategory
import net.dikkenberg.activiteitenweger.model.ActivityConflictSnapshot
import net.dikkenberg.activiteitenweger.model.ActivityItem
import net.dikkenberg.activiteitenweger.model.ActivityPreset
import net.dikkenberg.activiteitenweger.model.ActivityRecordPayload
import net.dikkenberg.activiteitenweger.model.DeviceInfo
import net.dikkenberg.activiteitenweger.model.PairingInvitation
import net.dikkenberg.activiteitenweger.model.ProfileSettingsPayload
import net.dikkenberg.activiteitenweger.model.SyncStatus
import net.dikkenberg.activiteitenweger.model.VaultSession
import net.dikkenberg.activiteitenweger.network.ApiClient
import net.dikkenberg.activiteitenweger.network.ApiException
import net.dikkenberg.activiteitenweger.network.ClaimPairingRequest
import net.dikkenberg.activiteitenweger.network.CreatePairingInviteRequest
import net.dikkenberg.activiteitenweger.network.CreateVaultRequest
import net.dikkenberg.activiteitenweger.network.RemoteRecord
import net.dikkenberg.activiteitenweger.network.UpdateDeviceLabelRequest
import net.dikkenberg.activiteitenweger.network.UpsertRecordRequest
import net.dikkenberg.activiteitenweger.storage.CachedEncryptedRecord
import net.dikkenberg.activiteitenweger.storage.LocalSyncStore
import net.dikkenberg.activiteitenweger.storage.LocalVaultSyncState
import net.dikkenberg.activiteitenweger.storage.PendingMutationState
import net.dikkenberg.activiteitenweger.storage.PendingRecordMutation
import net.dikkenberg.activiteitenweger.storage.SessionStore
import kotlin.time.Clock
import kotlin.uuid.Uuid

class VaultRepository(
    private val api: ApiClient,
    private val crypto: CryptoService,
    private val sessions: SessionStore,
    private val json: Json = api.json,
    private val localSyncStore: LocalSyncStore = LocalSyncStore(json),
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

    suspend fun createPairingInvitation(
        session: VaultSession,
        access: AccessMode,
        expiresInSeconds: Int = 600,
    ): PairingInvitation {
        check(session.owner) { "Alleen de eigenaar kan een apparaat koppelen" }
        check(session.access == AccessMode.RW) { "Schrijfrechten zijn vereist" }

        val inviteId = Uuid.random().toString()
        val secret = crypto.randomBytes(16)
        val verificationHash = crypto.sha256(
            "AW-PAIRING-VERIFY-V1\n".encodeToByteArray() + secret
        )
        val wrappingKey = crypto.sha256(
            "AW-PAIRING-WRAP-V1\n".encodeToByteArray() + secret
        )
        val nonce = crypto.randomBytes(24)
        val packagePayload = PairingKeyPackage(
            vaultId = session.vaultId,
            vaultKey = session.vaultKey,
            label = session.label,
            keyEpoch = session.keyEpoch,
        )
        val ciphertext = crypto.xChaCha20Poly1305Encrypt(
            key = wrappingKey,
            nonce24 = nonce,
            plaintext = json.encodeToString(packagePayload).encodeToByteArray(),
            associatedData = pairingAssociatedData(inviteId, session.vaultId, access),
        )

        val response = api.createPairingInvite(
            session,
            CreatePairingInviteRequest(
                inviteId = inviteId,
                access = access,
                pairingSecretHash = verificationHash.toBase64Url(),
                keyPackageCiphertext = ciphertext.toBase64Url(),
                keyPackageNonce = nonce.toBase64Url(),
                expiresInSeconds = expiresInSeconds,
            ),
        )

        val compactCode = secret.hexUpper()
        return PairingInvitation(
            inviteId = response.inviteId,
            code = compactCode.groupedPairingCode(),
            qrPayload = "AWPAIR1:$compactCode",
            access = response.access,
            expiresInSeconds = response.expiresInSeconds,
        )
    }

    suspend fun claimPairing(codeOrQr: String): VaultSession {
        val compact = codeOrQr.trim()
            .removePrefix("AWPAIR1:")
            .removePrefix("awpair1:")
            .filter(Char::isLetterOrDigit)
        val secret = compact.fromHexFlexible()
        require(secret.size == 16) { "Ongeldige koppelcode" }

        val signing = crypto.generateEd25519KeyPair()
        val encryption = crypto.generateX25519KeyPair()
        val deviceId = Uuid.random().toString()

        val response = api.claimPairing(
            ClaimPairingRequest(
                pairingSecret = secret.toBase64Url(),
                deviceId = deviceId,
                authPublicKey = signing.publicKey.toBase64Url(),
                encryptionPublicKey = encryption.publicKey.toBase64Url(),
            )
        )

        val wrappingKey = crypto.sha256(
            "AW-PAIRING-WRAP-V1\n".encodeToByteArray() + secret
        )
        val plaintext = crypto.xChaCha20Poly1305Decrypt(
            key = wrappingKey,
            nonce24 = response.keyPackageNonce.fromBase64Url(),
            ciphertext = response.keyPackageCiphertext.fromBase64Url(),
            associatedData = pairingAssociatedData(response.inviteId, response.vaultId, response.access),
        )
        val keyPackage = json.decodeFromString<PairingKeyPackage>(plaintext.decodeToString())
        check(keyPackage.vaultId == response.vaultId) { "Koppelpakket hoort bij een andere vault" }
        check(keyPackage.keyEpoch == response.keyEpoch) { "Koppelpakket heeft een onjuiste key epoch" }

        val session = VaultSession(
            vaultId = response.vaultId,
            deviceId = response.deviceId,
            label = keyPackage.label.ifBlank { "Gekoppelde Activiteitenweger" },
            access = response.access,
            owner = response.owner,
            keyEpoch = response.keyEpoch,
            authPrivateKey = signing.privateKey.toBase64Url(),
            authPublicKey = signing.publicKey.toBase64Url(),
            encryptionPrivateKey = encryption.privateKey.toBase64Url(),
            encryptionPublicKey = encryption.publicKey.toBase64Url(),
            vaultKey = keyPackage.vaultKey,
            cursor = 0,
        )
        sessions.save(session)
        return session
    }

    suspend fun devices(session: VaultSession): List<DeviceInfo> =
        api.devices(session).devices.map {
            val name = if (it.labelCiphertext != null && it.labelNonce != null) {
                runCatching {
                    crypto.xChaCha20Poly1305Decrypt(
                        key = session.vaultKey.fromBase64Url(),
                        nonce24 = it.labelNonce.fromBase64Url(),
                        ciphertext = it.labelCiphertext.fromBase64Url(),
                        associatedData = deviceLabelAssociatedData(session.vaultId, it.deviceId),
                    ).decodeToString()
                }.getOrNull()
            } else {
                null
            }
            DeviceInfo(
                deviceId = it.deviceId,
                access = it.access,
                owner = it.owner,
                status = it.status,
                createdAt = it.createdAt,
                lastSeenAt = it.lastSeenAt,
                revokedAt = it.revokedAt,
                name = name?.trim()?.takeIf(String::isNotBlank),
            )
        }

    suspend fun updateDeviceName(session: VaultSession, name: String) {
        val clean = name.trim()
        require(clean.isNotBlank()) { "Vul een naam voor dit apparaat in" }
        require(clean.length <= 80) { "De apparaatnaam mag maximaal 80 tekens zijn" }
        val nonce = crypto.randomBytes(24)
        val ciphertext = crypto.xChaCha20Poly1305Encrypt(
            key = session.vaultKey.fromBase64Url(),
            nonce24 = nonce,
            plaintext = clean.encodeToByteArray(),
            associatedData = deviceLabelAssociatedData(session.vaultId, session.deviceId),
        )
        api.updateDeviceLabel(
            session,
            UpdateDeviceLabelRequest(
                labelCiphertext = ciphertext.toBase64Url(),
                labelNonce = nonce.toBase64Url(),
            ),
        )
    }

    suspend fun refreshSessionAccess(session: VaultSession): VaultSession {
        val me = api.me(session)
        val updated = session.copy(
            access = me.access,
            owner = me.owner,
            keyEpoch = me.currentKeyEpoch,
        )
        sessions.save(updated)
        return updated
    }

    suspend fun transferOwnership(session: VaultSession, deviceId: String): VaultSession {
        check(session.owner) { "Alleen de eigenaar kan het eigenaarschap overdragen" }
        check(deviceId != session.deviceId) { "Dit apparaat is al eigenaar" }
        api.transferOwnership(session, deviceId)
        val updated = session.copy(owner = false)
        sessions.save(updated)
        return updated
    }

    suspend fun revokeDevice(session: VaultSession, deviceId: String) {
        check(session.owner) { "Alleen de eigenaar kan toegang intrekken" }
        api.revokeDevice(session, deviceId)
    }

    suspend fun selfRevoke(session: VaultSession) {
        check(!session.owner) { "De eigenaar kan de eigen toegang niet intrekken" }
        api.selfRevoke(session)
        localSyncStore.remove(session.vaultId)
        sessions.remove(session.vaultId)
    }

    suspend fun revokePairingInvitation(session: VaultSession, inviteId: String) {
        check(session.owner) { "Alleen de eigenaar kan een koppeling intrekken" }
        api.revokePairingInvite(session, inviteId)
    }

    suspend fun firstConflictSnapshot(session: VaultSession): ActivityConflictSnapshot? {
        val state = localSyncStore.load(session.vaultId)
        val recordId = state.pending.firstOrNull {
            it.state == PendingMutationState.CONFLICT
        }?.recordId ?: return null
        return conflictSnapshot(session, recordId)
    }

    suspend fun conflictSnapshot(session: VaultSession, recordId: String): ActivityConflictSnapshot? {
        val state = localSyncStore.load(session.vaultId)
        val pending = state.pending.firstOrNull {
            it.recordId == recordId && it.state == PendingMutationState.CONFLICT
        } ?: return null
        val remote = state.records.firstOrNull { it.recordId == recordId }

        val localPayload = if (!pending.deleted && pending.nonce != null && pending.ciphertext != null) {
            runCatching { decryptActivity(session, recordId, pending.nonce, pending.ciphertext) }.getOrNull()
        } else null

        val remotePayload = if (remote != null && !remote.deleted && remote.nonce != null && remote.ciphertext != null) {
            runCatching { decryptActivity(session, recordId, remote.nonce, remote.ciphertext) }.getOrNull()
        } else null

        return ActivityConflictSnapshot(
            recordId = recordId,
            localRevision = pending.revision,
            remoteRevision = remote?.revision ?: pending.baseRevision,
            localPayload = localPayload,
            remotePayload = remotePayload,
            localDeleted = pending.deleted,
            remoteDeleted = remote?.deleted ?: false,
        )
    }

    suspend fun resolveConflictUseServer(session: VaultSession, recordId: String): Pair<VaultSession, List<ActivityItem>> {
        val state = localSyncStore.load(session.vaultId)
        val conflict = state.pending.firstOrNull {
            it.recordId == recordId && it.state == PendingMutationState.CONFLICT
        } ?: error("Synchronisatieconflict niet gevonden")
        localSyncStore.save(
            session.vaultId,
            state.copy(pending = state.pending.filterNot { it.recordId == conflict.recordId }),
        )
        return loadCachedActivities(session)
    }

    suspend fun resolveConflictKeepMine(session: VaultSession, recordId: String): Pair<VaultSession, List<ActivityItem>> {
        val state = localSyncStore.load(session.vaultId)
        val conflict = state.pending.firstOrNull {
            it.recordId == recordId && it.state == PendingMutationState.CONFLICT
        } ?: error("Synchronisatieconflict niet gevonden")
        val remote = state.records.firstOrNull { it.recordId == recordId }
            ?: error("Serverversie niet gevonden")

        val plaintext = if (conflict.deleted) null else decryptPlaintext(
            session,
            conflict.recordId,
            requireNotNull(conflict.nonce),
            requireNotNull(conflict.ciphertext),
        )

        localSyncStore.save(
            session.vaultId,
            state.copy(pending = state.pending.filterNot { it.recordId == conflict.recordId }),
        )
        queueEncryptedMutation(
            session = session,
            recordId = conflict.recordId,
            fallbackBaseRevision = remote.revision,
            plaintext = plaintext,
            deleted = conflict.deleted,
        )
        return loadCachedActivities(session)
    }

    suspend fun loadCachedActivities(
        session: VaultSession,
    ): Pair<VaultSession, List<ActivityItem>> {
        val state = localSyncStore.load(session.vaultId)
        val updated = applyProfileSettingsFromState(
            session.copy(cursor = state.cursor),
            state,
        )
        return updated to activitiesFromState(updated, state)
    }

    suspend fun pendingCount(vaultId: String): Int =
        localSyncStore.load(vaultId).pending.count { it.state == PendingMutationState.PENDING }

    suspend fun conflictCount(vaultId: String): Int =
        localSyncStore.load(vaultId).pending.count { it.state == PendingMutationState.CONFLICT }

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
        var state = localSyncStore.load(session.vaultId)
        var current = session.copy(cursor = state.cursor)

        // Existing installations do not have a cache yet. Only in that case does
        // a requested full refresh start from cursor 0. Once the encrypted cache
        // exists, the persisted cursor is authoritative across app restarts.
        if (
            fullRefresh &&
            state.cursor == 0L &&
            state.records.isEmpty() &&
            state.pending.isEmpty()
        ) {
            state = LocalVaultSyncState()
        }

        val pulled = pullRemote(current, state)
        current = pulled.first
        state = pulled.second

        if (
            state.records.none { it.recordId == settingsRecordId(current) } &&
            state.pending.none { it.recordId == settingsRecordId(current) } &&
            current.settingsRevision == 0L &&
            current.access == AccessMode.RW
        ) {
            current = queueProfileSettingsMutation(
                session = current,
                label = current.label,
                categories = current.categories,
                activityPresets = current.activityPresets,
            )
            state = localSyncStore.load(current.vaultId)
        }

        val pushed = pushPending(current, state)
        current = pushed.session
        state = pushed.state

        if (pushed.didPush) {
            val afterPush = pullRemote(current, state)
            current = afterPush.first
            state = afterPush.second
        }

        current = applyProfileSettingsFromState(
            current.copy(cursor = state.cursor),
            state,
        )
        sessions.save(current)

        if (pushed.conflict != null) {
            throw pushed.conflict
        }

        return current to activitiesFromState(current, state)
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

        val recordId = Uuid.random().toString()
        val payload = ActivityRecordPayload(
            startedAt = startedAt,
            endedAt = endedAt,
            description = description.trim().ifBlank { "Activiteit" },
            category = category,
        )
        val pending = queueEncryptedMutation(
            session = session,
            recordId = recordId,
            fallbackBaseRevision = 0,
            plaintext = json.encodeToString(payload).encodeToByteArray(),
            deleted = false,
        ) ?: error("Nieuwe activiteit kon niet lokaal worden opgeslagen")

        return ActivityItem(
            recordId = recordId,
            revision = pending.revision,
            payload = payload,
            syncStatus = SyncStatus.PENDING,
        )
    }

    suspend fun stopActivity(session: VaultSession, item: ActivityItem): ActivityItem {
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        check(item.syncStatus != SyncStatus.CONFLICT) {
            "Los eerst het synchronisatieconflict voor deze activiteit op"
        }

        val payload = item.payload.copy(endedAt = Clock.System.now().toString())
        val pending = queueEncryptedMutation(
            session = session,
            recordId = item.recordId,
            fallbackBaseRevision = item.revision,
            plaintext = json.encodeToString(payload).encodeToByteArray(),
            deleted = false,
        ) ?: error("Activiteit kon niet lokaal worden opgeslagen")

        return item.copy(
            revision = pending.revision,
            payload = payload,
            syncStatus = SyncStatus.PENDING,
        )
    }

    suspend fun updateActivity(session: VaultSession, item: ActivityItem): ActivityItem {
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        check(item.syncStatus != SyncStatus.CONFLICT) {
            "Los eerst het synchronisatieconflict voor deze activiteit op"
        }

        val pending = queueEncryptedMutation(
            session = session,
            recordId = item.recordId,
            fallbackBaseRevision = item.revision,
            plaintext = json.encodeToString(item.payload).encodeToByteArray(),
            deleted = false,
        ) ?: error("Activiteit kon niet lokaal worden opgeslagen")

        return item.copy(
            revision = pending.revision,
            syncStatus = SyncStatus.PENDING,
        )
    }

    suspend fun deleteActivity(session: VaultSession, item: ActivityItem) {
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        check(item.syncStatus != SyncStatus.CONFLICT) {
            "Los eerst het synchronisatieconflict voor deze activiteit op"
        }

        queueEncryptedMutation(
            session = session,
            recordId = item.recordId,
            fallbackBaseRevision = item.revision,
            plaintext = null,
            deleted = true,
        )
    }

    suspend fun updateProfileSettings(
        session: VaultSession,
        label: String = session.label,
        categories: List<ActivityCategory> = session.categories,
        activityPresets: List<ActivityPreset> = session.activityPresets,
    ): VaultSession =
        queueProfileSettingsMutation(
            session = session,
            label = label,
            categories = categories,
            activityPresets = activityPresets,
        )

    suspend fun deleteVault(session: VaultSession) {
        check(session.owner) { "Alleen de eigenaar kan de volledige vault verwijderen" }
        api.deleteVault(session)
        localSyncStore.remove(session.vaultId)
        sessions.remove(session.vaultId)
    }

    private suspend fun queueProfileSettingsMutation(
        session: VaultSession,
        label: String,
        categories: List<ActivityCategory>,
        activityPresets: List<ActivityPreset>,
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
        val pending = queueEncryptedMutation(
            session = session,
            recordId = settingsRecordId(session),
            fallbackBaseRevision = session.settingsRevision,
            plaintext = json.encodeToString(payload).encodeToByteArray(),
            deleted = false,
        ) ?: error("Profielinstellingen konden niet lokaal worden opgeslagen")

        val updated = session.copy(
            label = payload.label,
            categories = payload.categories,
            activityPresets = payload.activityPresets,
            settingsRevision = pending.revision,
        )
        sessions.save(updated)
        return updated
    }

    private suspend fun queueEncryptedMutation(
        session: VaultSession,
        recordId: String,
        fallbackBaseRevision: Long,
        plaintext: ByteArray?,
        deleted: Boolean,
    ): PendingRecordMutation? {
        var state = localSyncStore.load(session.vaultId)
        val existing = state.pending.firstOrNull { it.recordId == recordId }
        check(existing?.state != PendingMutationState.CONFLICT) {
            "Dit item heeft een synchronisatieconflict"
        }

        val cached = state.records.firstOrNull { it.recordId == recordId }
        val baseRevision = existing?.baseRevision
            ?: cached?.revision
            ?: fallbackBaseRevision.coerceAtLeast(0)

        if (deleted && existing != null && existing.baseRevision == 0L) {
            state = state.copy(
                pending = state.pending.filterNot { it.recordId == recordId },
                records = state.records.filterNot { it.recordId == recordId },
            )
            localSyncStore.save(session.vaultId, state)
            return null
        }

        val revision = existing?.revision ?: (baseRevision + 1)
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
        val mutation = PendingRecordMutation(
            recordId = recordId,
            baseRevision = baseRevision,
            revision = revision,
            keyEpoch = session.keyEpoch,
            deleted = deleted,
            ciphertext = ciphertext?.toBase64Url(),
            nonce = nonce?.toBase64Url(),
            recordSignature = signature.toBase64Url(),
            localChangedAt = Clock.System.now().toString(),
        )

        state = state.copy(
            pending = state.pending.filterNot { it.recordId == recordId } + mutation,
        )
        localSyncStore.save(session.vaultId, state)
        return mutation
    }

    private suspend fun pullRemote(
        session: VaultSession,
        initialState: LocalVaultSyncState,
    ): Pair<VaultSession, LocalVaultSyncState> {
        var current = session
        var state = initialState
        var cursor = state.cursor

        do {
            val response = api.sync(
                session = current.copy(cursor = cursor),
                since = cursor,
                limit = 500,
            )
            current = current.copy(keyEpoch = response.currentKeyEpoch)

            val records = state.records.associateByTo(linkedMapOf()) { it.recordId }
            val pending = state.pending.associateByTo(linkedMapOf()) { it.recordId }

            response.records.forEach { remote ->
                val localPending = pending[remote.recordId]
                when {
                    localPending == null -> {
                        records[remote.recordId] = remote.toCachedRecord()
                    }

                    isConfirmationOfOwnWrite(current, remote, localPending) -> {
                        records[remote.recordId] = remote.toCachedRecord()
                        pending.remove(remote.recordId)
                    }

                    remote.revision > localPending.baseRevision -> {
                        records[remote.recordId] = remote.toCachedRecord()
                        pending[remote.recordId] = localPending.copy(
                            state = PendingMutationState.CONFLICT,
                        )
                    }

                    else -> {
                        records[remote.recordId] = remote.toCachedRecord()
                    }
                }
            }

            cursor = response.nextCursor
            state = state.copy(
                cursor = cursor,
                records = records.values.toList(),
                pending = pending.values.toList(),
            )

            // Persist the page before moving the session cursor forward.
            localSyncStore.save(current.vaultId, state)
            sessions.save(current.copy(cursor = cursor))
        } while (response.hasMore)

        return current.copy(cursor = cursor) to state
    }

    private suspend fun pushPending(
        session: VaultSession,
        initialState: LocalVaultSyncState,
    ): PushResult {
        var state = initialState
        var firstConflict: ApiException? = null
        var didPush = false

        val candidates = state.pending
            .filter { it.state == PendingMutationState.PENDING }
            .sortedBy { it.localChangedAt }

        for (mutation in candidates) {
            try {
                api.upsertRecord(
                    session,
                    mutation.toRequest(),
                )

                didPush = true
                val record = CachedEncryptedRecord(
                    recordId = mutation.recordId,
                    revision = mutation.revision,
                    keyEpoch = mutation.keyEpoch,
                    deleted = mutation.deleted,
                    ciphertext = mutation.ciphertext,
                    nonce = mutation.nonce,
                    writerDeviceId = session.deviceId,
                    recordSignature = mutation.recordSignature,
                    updatedAt = null,
                    deletedAt = null,
                )
                state = state.copy(
                    records = state.records
                        .filterNot { it.recordId == mutation.recordId } + record,
                    pending = state.pending.filterNot { it.recordId == mutation.recordId },
                )
                localSyncStore.save(session.vaultId, state)
            } catch (e: ApiException) {
                if (e.isRevisionConflict) {
                    state = state.copy(
                        pending = state.pending.map {
                            if (it.recordId == mutation.recordId) {
                                it.copy(state = PendingMutationState.CONFLICT)
                            } else {
                                it
                            }
                        },
                    )
                    localSyncStore.save(session.vaultId, state)
                    if (firstConflict == null) {
                        firstConflict = e
                    }
                    continue
                }
                throw e
            }
        }

        return PushResult(
            session = session,
            state = state,
            didPush = didPush,
            conflict = firstConflict,
        )
    }

    private suspend fun activitiesFromState(
        session: VaultSession,
        state: LocalVaultSyncState,
    ): List<ActivityItem> {
        val records = state.records.associateBy { it.recordId }
        val pending = state.pending.associateBy { it.recordId }
        val ids = (records.keys + pending.keys)
            .filterNot { it == settingsRecordId(session) }

        return ids.mapNotNull { recordId ->
            val localPending = pending[recordId]
            if (localPending != null) {
                if (localPending.deleted) {
                    return@mapNotNull null
                }
                val payload = decryptActivity(
                    session = session,
                    recordId = recordId,
                    nonce = requireNotNull(localPending.nonce),
                    ciphertext = requireNotNull(localPending.ciphertext),
                )
                return@mapNotNull ActivityItem(
                    recordId = recordId,
                    revision = localPending.revision,
                    payload = payload,
                    syncStatus = if (localPending.state == PendingMutationState.CONFLICT) {
                        SyncStatus.CONFLICT
                    } else {
                        SyncStatus.PENDING
                    },
                )
            }

            val record = records[recordId] ?: return@mapNotNull null
            if (record.deleted || record.ciphertext == null || record.nonce == null) {
                return@mapNotNull null
            }
            val payload = decryptActivity(
                session = session,
                recordId = record.recordId,
                nonce = record.nonce,
                ciphertext = record.ciphertext,
            )
            ActivityItem(
                recordId = record.recordId,
                revision = record.revision,
                payload = payload,
                syncStatus = SyncStatus.SYNCED,
            )
        }.sortedByDescending { it.payload.startedAt }
    }

    private suspend fun applyProfileSettingsFromState(
        session: VaultSession,
        state: LocalVaultSyncState,
    ): VaultSession {
        val recordId = settingsRecordId(session)
        val localPending = state.pending.firstOrNull { it.recordId == recordId }

        if (
            localPending != null &&
            !localPending.deleted &&
            localPending.ciphertext != null &&
            localPending.nonce != null
        ) {
            val payload = decryptProfileSettings(
                session = session,
                recordId = recordId,
                nonce = localPending.nonce,
                ciphertext = localPending.ciphertext,
            )
            return session.withSettings(payload, localPending.revision)
        }

        val record = state.records.firstOrNull { it.recordId == recordId }
            ?: return session
        if (record.deleted || record.ciphertext == null || record.nonce == null) {
            return session.copy(settingsRevision = record.revision)
        }

        val payload = decryptProfileSettings(
            session = session,
            recordId = recordId,
            nonce = record.nonce,
            ciphertext = record.ciphertext,
        )
        return session.withSettings(payload, record.revision)
    }

    private fun VaultSession.withSettings(
        payload: ProfileSettingsPayload,
        revision: Long,
    ): VaultSession {
        val categories = payload.categories.ifEmpty { ActivityCategory.defaults }
        val categoryIds = categories.mapTo(mutableSetOf()) { it.id }
        return copy(
            label = payload.label.trim().ifBlank { label },
            categories = categories,
            activityPresets = payload.activityPresets.filter { it.categoryId in categoryIds },
            settingsRevision = revision,
        )
    }

    private fun isConfirmationOfOwnWrite(
        session: VaultSession,
        remote: RemoteRecord,
        pending: PendingRecordMutation,
    ): Boolean =
        remote.revision == pending.revision &&
            remote.writerDeviceId == session.deviceId &&
            remote.recordSignature == pending.recordSignature

    private fun RemoteRecord.toCachedRecord(): CachedEncryptedRecord =
        CachedEncryptedRecord(
            recordId = recordId,
            revision = revision,
            keyEpoch = keyEpoch,
            deleted = deleted,
            ciphertext = ciphertext,
            nonce = nonce,
            writerDeviceId = writerDeviceId,
            recordSignature = recordSignature,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
        )

    private fun PendingRecordMutation.toRequest(): UpsertRecordRequest =
        UpsertRecordRequest(
            recordId = recordId,
            revision = revision,
            keyEpoch = keyEpoch,
            deleted = deleted,
            ciphertext = ciphertext,
            nonce = nonce,
            recordSignature = recordSignature,
        )

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

    private fun deviceLabelAssociatedData(vaultId: String, deviceId: String): ByteArray =
        ("AW-DEVICE-LABEL-V1\n" + vaultId + "\n" + deviceId).encodeToByteArray()

    private fun pairingAssociatedData(
        inviteId: String,
        vaultId: String,
        access: AccessMode,
    ): ByteArray =
        ("AW-PAIRING-V1\n" + inviteId + "\n" + vaultId + "\n" + access.name).encodeToByteArray()

    @Serializable
    private data class PairingKeyPackage(
        val schemaVersion: Int = 1,
        val vaultId: String,
        val vaultKey: String,
        val label: String,
        val keyEpoch: Int,
    )
    private data class PushResult(
        val session: VaultSession,
        val state: LocalVaultSyncState,
        val didPush: Boolean,
        val conflict: ApiException?,
    )
}
