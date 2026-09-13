// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.dikkenberg.activiteitenweger.crypto.CryptoService
import net.dikkenberg.activiteitenweger.data.VaultRepository
import net.dikkenberg.activiteitenweger.excel.ExcelTransfer
import net.dikkenberg.activiteitenweger.model.AccessMode
import net.dikkenberg.activiteitenweger.model.ActivityCategory
import net.dikkenberg.activiteitenweger.model.ActivityItem
import net.dikkenberg.activiteitenweger.model.ActivityPreset
import net.dikkenberg.activiteitenweger.model.VaultSession
import net.dikkenberg.activiteitenweger.network.ApiClient
import net.dikkenberg.activiteitenweger.network.ApiException
import net.dikkenberg.activiteitenweger.platform.pickExcelFileBytes
import net.dikkenberg.activiteitenweger.platform.saveExcelFile
import net.dikkenberg.activiteitenweger.storage.SessionStore
import net.dikkenberg.activiteitenweger.storage.createSecureStore
import kotlin.time.Clock
import kotlin.uuid.Uuid


data class SyncConflictInfo(
    val recordId: String?,
    val currentRevision: Long?,
    val expectedRevision: Long?,
    val currentDeleted: Boolean?,
    val currentUpdatedAt: String?,
)

data class AppUiState(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val sessions: List<VaultSession> = emptyList(),
    val selectedVaultId: String? = null,
    val activities: List<ActivityItem> = emptyList(),
    val health: String = "Onbekend",
    val serverVersion: String? = null,
    val syncing: Boolean = false,
    val lastSyncAt: String? = null,
    val pendingChanges: Int = 0,
    val conflictChanges: Int = 0,
    val conflict: SyncConflictInfo? = null,
    val message: String? = null,
    val error: String? = null,
) {
    val selectedSession: VaultSession?
        get() = sessions.firstOrNull { it.vaultId == selectedVaultId }

    val activeActivity: ActivityItem?
        get() = activities.firstOrNull { it.payload.endedAt == null }

    val canWrite: Boolean
        get() = selectedSession?.access == AccessMode.RW

    val categories: List<ActivityCategory>
        get() = selectedSession?.categories ?: ActivityCategory.defaults

    val activityPresets: List<ActivityPreset>
        get() = selectedSession?.activityPresets.orEmpty()
}

class AppController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val crypto = CryptoService()
    private val api = ApiClient(crypto = crypto)
    private val sessionStore = SessionStore(createSecureStore(), api.json)
    private val repository = VaultRepository(api, crypto, sessionStore)
    private val operationMutex = Mutex()
    private var automaticSyncJob: Job? = null
    private var appInForeground: Boolean = true

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    fun initialize() {
        if (_state.value.initialized) return
        scope.launch {
            val sessions = repository.sessions()
            val selected = sessions.firstOrNull()?.vaultId
            _state.value = _state.value.copy(
                initialized = true,
                sessions = sessions,
                selectedVaultId = selected,
            )
            checkHealth()
            if (selected != null) {
                operationMutex.withLock {
                    loadCachedSelectedLocked()
                }
                // Cache is direct beschikbaar; netwerk-sync mag de UI niet blokkeren.
                syncCurrentSilently()
            }
        }
    }

    fun createVault(label: String) = launchBusy(syncAfter = true) {
        val session = repository.createVault(label)
        _state.value = _state.value.copy(
            sessions = repository.sessions(),
            selectedVaultId = session.vaultId,
            activities = emptyList(),
            message = "Activiteitenweger aangemaakt",
        )
        loadCachedSelectedLocked()
    }

    fun selectVault(vaultId: String) {
        scope.launch {
            // Voorkom dat een sync van het vorige profiel na de wissel nog UI-state terugschrijft.
            automaticSyncJob?.cancelAndJoin()

            _state.value = _state.value.copy(busy = true, error = null)
            operationMutex.withLock {
                _state.value = _state.value.copy(
                    selectedVaultId = vaultId,
                    activities = emptyList(),
                    conflict = null,
                )
                loadCachedSelectedLocked()
            }
            _state.value = _state.value.copy(busy = false)

            // Netwerk volgt los; het gekozen profiel is al direct lokaal bruikbaar.
            syncCurrentSilently()
        }
    }

    fun syncCurrent() {
        scope.launch {
            runSync(
                fullRefresh = false,
                announce = true,
                showBusy = true,
            )
        }
    }

    fun syncCurrentSilently() {
        if (
            !appInForeground ||
            !_state.value.initialized ||
            _state.value.selectedSession == null ||
            _state.value.busy ||
            _state.value.syncing ||
            automaticSyncJob?.isActive == true
        ) return

        automaticSyncJob = scope.launch {
            try {
                runSync(
                    fullRefresh = false,
                    announce = false,
                    showBusy = false,
                )
            } finally {
                automaticSyncJob = null
            }
        }
    }

    fun setAppForeground(active: Boolean) {
        val becameActive = active && !appInForeground
        appInForeground = active
        if (becameActive) {
            syncCurrentSilently()
        }
    }

    fun startActivity(description: String, category: ActivityCategory) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        check(_state.value.activeActivity == null) { "Er loopt al een activiteit" }
        val created = repository.startActivity(session, description, category)
        _state.value = _state.value.copy(
            activities = listOf(created) + _state.value.activities,
            message = "Activiteit gestart",
        )
    }

    fun stopActiveActivity() = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        val active = requireNotNull(_state.value.activeActivity)
        val updated = repository.stopActivity(session, active)
        _state.value = _state.value.copy(
            activities = _state.value.activities.map {
                if (it.recordId == updated.recordId) updated else it
            },
            message = "Activiteit afgerond",
        )
    }

    fun addManualActivity(
        description: String,
        category: ActivityCategory,
        startDate: String,
        startTime: String,
        endDate: String,
        endTime: String,
    ) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        val startedAt = localDateTimeToInstant(startDate, startTime)
        val endedAt = localDateTimeToInstant(endDate, endTime)
        check(kotlin.time.Instant.parse(endedAt) >= kotlin.time.Instant.parse(startedAt)) {
            "Eindtijd mag niet vóór de starttijd liggen"
        }
        val created = repository.createActivity(
            session = session,
            description = description,
            category = category,
            startedAt = startedAt,
            endedAt = endedAt,
        )
        _state.value = _state.value.copy(
            activities = (listOf(created) + _state.value.activities)
                .sortedByDescending { it.payload.startedAt },
            message = "Activiteit handmatig toegevoegd",
        )
    }

    fun updateActivity(
        item: ActivityItem,
        description: String,
        category: ActivityCategory,
        startDate: String,
        startTime: String,
        endDate: String,
        endTime: String,
    ) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        val startedAt = localDateTimeToInstant(startDate, startTime)
        val endedAt = localDateTimeToInstant(endDate, endTime)
        check(kotlin.time.Instant.parse(endedAt) >= kotlin.time.Instant.parse(startedAt)) {
            "Eindtijd mag niet vóór de starttijd liggen"
        }
        val updated = repository.updateActivity(
            session,
            item.copy(
                payload = item.payload
                    .copy(
                        startedAt = startedAt,
                        endedAt = endedAt,
                        description = description.trim().ifBlank { "Activiteit" },
                    )
                    .withCategory(category)
            )
        )
        _state.value = _state.value.copy(
            activities = _state.value.activities
                .map { if (it.recordId == updated.recordId) updated else it }
                .sortedByDescending { it.payload.startedAt },
            message = "Activiteit gewijzigd",
        )
    }

    fun renameProfile(vaultId: String, label: String) = launchBusy(conflictVaultId = vaultId, syncAfter = true) {
        val session = _state.value.sessions.first { it.vaultId == vaultId }
        val updated = repository.updateProfileSettings(
            session = session,
            label = label,
        )
        replaceSession(updated)
        _state.value = _state.value.copy(message = "Profielnaam gewijzigd")
    }

    fun saveCategory(
        categoryId: String?,
        label: String,
        pointsPer30Minutes: Double,
    ) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }

        val cleanLabel = label.trim()
        require(cleanLabel.isNotBlank()) { "Vul een categorienaam in" }
        require(pointsPer30Minutes.isFinite()) { "Vul een geldig puntenaantal in" }

        val duplicate = session.categories.any {
            it.id != categoryId && it.label.equals(cleanLabel, ignoreCase = true)
        }
        check(!duplicate) { "Er bestaat al een categorie met deze naam" }

        val updatedCategory = ActivityCategory(
            id = categoryId ?: "custom-${Uuid.random()}",
            label = cleanLabel,
            pointsPer30Minutes = pointsPer30Minutes,
        )
        val categories = if (categoryId == null) {
            session.categories + updatedCategory
        } else {
            session.categories.map {
                if (it.id == categoryId) updatedCategory else it
            }
        }

        val updated = repository.updateProfileSettings(
            session = session,
            categories = categories,
        )
        replaceSession(updated)
        _state.value = _state.value.copy(
            message = if (categoryId == null) "Categorie toegevoegd" else "Categorie gewijzigd",
        )
    }

    fun deleteCategory(categoryId: String) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        check(session.categories.size > 1) { "Er moet minimaal één categorie overblijven" }

        val categories = session.categories.filterNot { it.id == categoryId }
        check(categories.size != session.categories.size) { "Categorie niet gevonden" }
        check(session.activityPresets.none { it.categoryId == categoryId }) {
            "Deze categorie wordt nog gebruikt door een standaardactiviteit"
        }

        val updated = repository.updateProfileSettings(
            session = session,
            categories = categories,
        )
        replaceSession(updated)
        _state.value = _state.value.copy(message = "Categorie verwijderd")
    }

    fun saveActivityPreset(
        presetId: String?,
        label: String,
        categoryId: String,
    ) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }

        val cleanLabel = label.trim()
        require(cleanLabel.isNotBlank()) { "Vul een naam voor de standaardactiviteit in" }
        check(session.categories.any { it.id == categoryId }) { "Kies een geldige categorie" }

        val duplicate = session.activityPresets.any {
            it.id != presetId && it.label.equals(cleanLabel, ignoreCase = true)
        }
        check(!duplicate) { "Er bestaat al een standaardactiviteit met deze naam" }

        val updatedPreset = ActivityPreset(
            id = presetId ?: "preset-${Uuid.random()}",
            label = cleanLabel,
            categoryId = categoryId,
        )
        val presets = if (presetId == null) {
            session.activityPresets + updatedPreset
        } else {
            session.activityPresets.map {
                if (it.id == presetId) updatedPreset else it
            }
        }

        val updated = repository.updateProfileSettings(
            session = session,
            activityPresets = presets,
        )
        replaceSession(updated)
        _state.value = _state.value.copy(
            message = if (presetId == null) {
                "Standaardactiviteit toegevoegd"
            } else {
                "Standaardactiviteit gewijzigd"
            },
        )
    }

    fun deleteActivityPreset(presetId: String) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }

        val presets = session.activityPresets.filterNot { it.id == presetId }
        check(presets.size != session.activityPresets.size) {
            "Standaardactiviteit niet gevonden"
        }

        val updated = repository.updateProfileSettings(
            session = session,
            activityPresets = presets,
        )
        replaceSession(updated)
        _state.value = _state.value.copy(message = "Standaardactiviteit verwijderd")
    }

    fun deleteActivity(item: ActivityItem) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        repository.deleteActivity(session, item)
        _state.value = _state.value.copy(
            activities = _state.value.activities.filterNot { it.recordId == item.recordId },
            message = "Activiteit verwijderd",
        )
    }

    fun exportExcel() = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        val exportCategories = (session.categories + _state.value.activities.map { it.payload.category })
            .distinctBy { it.id }
        val bytes = ExcelTransfer.exportWorkbook(
            activities = _state.value.activities,
            categories = exportCategories,
        )
        val date = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toString()
        val label = safeFilePart(session.label)
        val saved = saveExcelFile(
            suggestedName = "Activiteitenweger-$label-$date",
            bytes = bytes,
        )
        if (saved) {
            _state.value = _state.value.copy(message = "Excel-bestand geëxporteerd")
        }
    }

    fun importExcel(fallbackYear: Int) = launchBusy(syncAfter = true) {
        val session = requireNotNull(_state.value.selectedSession)
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }

        val bytes = pickExcelFileBytes() ?: return@launchBusy
        val parsed = ExcelTransfer.importWorkbook(
            bytes = bytes,
            fallbackYear = fallbackYear,
            categories = session.categories,
        )

        val knownKeys = _state.value.activities
            .mapTo(mutableSetOf()) {
                activityKey(
                    startedAt = it.payload.startedAt,
                    endedAt = it.payload.endedAt,
                    description = it.payload.description,
                    category = it.payload.category,
                )
            }
        val importKeys = mutableSetOf<String>()
        val created = mutableListOf<ActivityItem>()
        var duplicates = 0

        try {
            for (row in parsed.activities) {
                val startedAt = localDateTimeToInstant(row.startDate.toString(), row.startTime.toString())
                val endedAt = localDateTimeToInstant(row.endDate.toString(), row.endTime.toString())
                val key = activityKey(
                    startedAt = startedAt,
                    endedAt = endedAt,
                    description = row.description,
                    category = row.category,
                )
                if (key in knownKeys || !importKeys.add(key)) {
                    duplicates++
                    continue
                }

                val item = repository.createActivity(
                    session = session,
                    description = row.description,
                    category = row.category,
                    startedAt = startedAt,
                    endedAt = endedAt,
                )
                created += item
                knownKeys += key
            }
        } finally {
            if (created.isNotEmpty()) {
                _state.value = _state.value.copy(
                    activities = (created + _state.value.activities)
                        .sortedByDescending { it.payload.startedAt },
                )
            }
        }

        val details = buildList {
            add("${created.size} toegevoegd")
            if (duplicates > 0) add("$duplicates dubbel")
            if (parsed.skippedRows > 0) add("${parsed.skippedRows} overgeslagen")
            if (parsed.ignoredSheets > 0) add("${parsed.ignoredSheets} blad(en) genegeerd")
        }.joinToString(", ")

        _state.value = _state.value.copy(message = "Excel geïmporteerd: $details")
    }

    fun deleteCurrentVault() = launchBusy {
        val session = requireNotNull(_state.value.selectedSession)
        repository.deleteVault(session)
        val sessions = repository.sessions()
        val selected = sessions.firstOrNull()?.vaultId
        _state.value = _state.value.copy(
            sessions = sessions,
            selectedVaultId = selected,
            activities = emptyList(),
            message = "Alle servergegevens van deze Activiteitenweger zijn verwijderd",
        )
        if (selected != null) {
            syncSelectedLocked(fullRefresh = true, announce = false)
        }
    }

    fun checkHealth() {
        scope.launch {
            runCatching { api.health() }
                .onSuccess { response ->
                    _state.value = _state.value.copy(
                        health = "Online",
                        serverVersion = response.serverVersion,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        health = "Offline",
                        serverVersion = null,
                    )
                }
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(
            message = null,
            error = null,
            conflict = null,
        )
    }

    private fun replaceSession(updated: VaultSession) {
        _state.value = _state.value.copy(
            sessions = _state.value.sessions.map {
                if (it.vaultId == updated.vaultId) updated else it
            },
        )
    }

    private fun activityKey(
        startedAt: String,
        endedAt: String?,
        description: String,
        category: ActivityCategory,
    ): String =
        listOf(
            startedAt,
            endedAt.orEmpty(),
            description.trim(),
            category.id,
        ).joinToString("|")

    private fun safeFilePart(value: String): String =
        value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "profiel" }

    private fun localDateTimeToInstant(date: String, time: String): String {
        val trimmedTime = time.trim()
        val normalizedTime = if (trimmedTime.count { it == ':' } == 1) "$trimmedTime:00" else trimmedTime
        return LocalDateTime.parse("${date.trim()}T$normalizedTime")
            .toInstant(TimeZone.currentSystemDefault())
            .toString()
    }

    private suspend fun loadCachedSelectedLocked() {
        val session = _state.value.selectedSession ?: return
        val (updatedSession, activities) = repository.loadCachedActivities(session)
        val sessions = repository.sessions().map {
            if (it.vaultId == updatedSession.vaultId) updatedSession else it
        }
        val pendingChanges = repository.pendingCount(updatedSession.vaultId)
        val conflictChanges = repository.conflictCount(updatedSession.vaultId)
        _state.value = _state.value.copy(
            sessions = sessions,
            activities = activities,
            pendingChanges = pendingChanges,
            conflictChanges = conflictChanges,
        )
    }

    private suspend fun syncSelectedLocked(
        fullRefresh: Boolean,
        announce: Boolean,
    ) {
        val before = _state.value
        val session = before.selectedSession ?: return
        _state.value = before.copy(syncing = true)

        try {
            val (updatedSession, activities) = repository.syncActivities(
                session = session,
                currentActivities = if (fullRefresh) emptyList() else before.activities,
                fullRefresh = fullRefresh,
            )
            val sessions = repository.sessions().map {
                if (it.vaultId == updatedSession.vaultId) updatedSession else it
            }
            val pendingChanges = repository.pendingCount(updatedSession.vaultId)
            val conflictChanges = repository.conflictCount(updatedSession.vaultId)
            _state.value = _state.value.copy(
                sessions = sessions,
                activities = activities,
                lastSyncAt = Clock.System.now().toString(),
                pendingChanges = pendingChanges,
                conflictChanges = conflictChanges,
                syncing = false,
                message = if (announce) {
                    if (pendingChanges == 0) "Gesynchroniseerd" else "$pendingChanges wijziging(en) wachten op synchronisatie"
                } else {
                    _state.value.message
                },
            )
        } catch (e: Throwable) {
            _state.value = _state.value.copy(syncing = false)
            throw e
        }
    }

    private suspend fun handleRevisionConflictLocked(
        exception: ApiException,
        conflictVaultId: String? = null,
    ) {
        val conflict = SyncConflictInfo(
            recordId = exception.recordId,
            currentRevision = exception.currentRevision,
            expectedRevision = exception.expectedRevision,
            currentDeleted = exception.currentDeleted,
            currentUpdatedAt = exception.currentUpdatedAt,
        )

        val targetVaultId = conflictVaultId ?: _state.value.selectedVaultId
        val refreshError = runCatching {
            if (targetVaultId == null || targetVaultId == _state.value.selectedVaultId) {
                syncSelectedLocked(
                    fullRefresh = false,
                    announce = false,
                )
            } else {
                val session = repository.sessions().firstOrNull { it.vaultId == targetVaultId }
                    ?: error("Profiel niet gevonden")
                val (updatedSession, _) = repository.syncActivities(
                    session = session,
                    currentActivities = emptyList(),
                    fullRefresh = true,
                )
                replaceSession(updatedSession)
            }
        }.exceptionOrNull()

        val revisionText = exception.currentRevision?.let { " Serverrevision: $it." }.orEmpty()
        val refreshText = if (refreshError == null) {
            " De nieuwste serverversie is geladen."
        } else {
            " Het opnieuw ophalen van de serverversie is ook mislukt."
        }

        _state.value = _state.value.copy(
            conflict = conflict,
            message = null,
            error = "Synchronisatieconflict: dit item is ondertussen op een ander apparaat gewijzigd. " +
                "Jouw wijziging is niet opgeslagen." + revisionText + refreshText,
        )
    }

    private suspend fun runSync(
        fullRefresh: Boolean,
        announce: Boolean,
        showBusy: Boolean,
    ) {
        if (showBusy) {
            _state.value = _state.value.copy(busy = true, error = null)
        }

        operationMutex.withLock {
            runCatching {
                syncSelectedLocked(
                    fullRefresh = fullRefresh,
                    announce = announce,
                )
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                loadCachedSelectedLocked()
                if (showBusy || announce) {
                    _state.value = _state.value.copy(
                        error = null,
                        message = "Geen verbinding · lokale gegevens blijven beschikbaar en wijzigingen worden later gesynchroniseerd",
                    )
                }
            }
        }

        if (showBusy) {
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun launchBusy(
        conflictVaultId: String? = null,
        syncAfter: Boolean = false,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            // Een automatische netwerk-sync mag een lokale Start/Stop/Wijzig
            // nooit blokkeren. Breek die eerst af en wacht tot de lokale mutex vrij is.
            automaticSyncJob?.cancelAndJoin()

            _state.value = _state.value.copy(
                busy = true,
                error = null,
                conflict = null,
            )

            var succeeded = false
            operationMutex.withLock {
                try {
                    block()
                    succeeded = true
                } catch (e: ApiException) {
                    if (e.isRevisionConflict) {
                        handleRevisionConflictLocked(e, conflictVaultId)
                    } else {
                        _state.value = _state.value.copy(
                            error = e.message ?: e::class.simpleName,
                        )
                    }
                } catch (e: Throwable) {
                    _state.value = _state.value.copy(
                        error = e.message ?: e::class.simpleName,
                    )
                }
            }

            _state.value = _state.value.copy(busy = false)

            // De lokale wijziging is nu duurzaam opgeslagen. Netwerk-sync gebeurt
            // pas daarna, los van de knopactie, zodat offline gebruik direct blijft werken.
            if (succeeded && syncAfter) {
                syncCurrentSilently()
            }
        }
    }

}
