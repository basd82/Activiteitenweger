// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import net.dikkenberg.activiteitenweger.model.VaultSession
import net.dikkenberg.activiteitenweger.network.ApiClient
import net.dikkenberg.activiteitenweger.platform.pickExcelFileBytes
import net.dikkenberg.activiteitenweger.platform.saveExcelFile
import net.dikkenberg.activiteitenweger.storage.SessionStore
import net.dikkenberg.activiteitenweger.storage.createSecureStore
import kotlin.time.Clock
import kotlin.uuid.Uuid


data class AppUiState(
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val sessions: List<VaultSession> = emptyList(),
    val selectedVaultId: String? = null,
    val activities: List<ActivityItem> = emptyList(),
    val health: String = "Onbekend",
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
}

class AppController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val crypto = CryptoService()
    private val api = ApiClient(crypto = crypto)
    private val sessionStore = SessionStore(createSecureStore(), api.json)
    private val repository = VaultRepository(api, crypto, sessionStore)

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
            if (selected != null) syncCurrent()
        }
    }

    fun createVault(label: String) = launchBusy {
        val session = repository.createVault(label)
        _state.value = _state.value.copy(
            sessions = repository.sessions(),
            selectedVaultId = session.vaultId,
            activities = emptyList(),
            message = "Activiteitenweger aangemaakt",
        )
    }

    fun selectVault(vaultId: String) {
        _state.value = _state.value.copy(selectedVaultId = vaultId, activities = emptyList())
        syncCurrent()
    }

    fun syncCurrent() = launchBusy {
        val session = _state.value.selectedSession ?: return@launchBusy
        val (updatedSession, activities) = repository.loadAllActivities(session)
        val sessions = repository.sessions().map {
            if (it.vaultId == updatedSession.vaultId) updatedSession else it
        }
        _state.value = _state.value.copy(
            sessions = sessions,
            activities = activities,
            message = "Gesynchroniseerd",
        )
    }

    fun startActivity(description: String, category: ActivityCategory) = launchBusy {
        val session = requireNotNull(_state.value.selectedSession)
        check(_state.value.activeActivity == null) { "Er loopt al een activiteit" }
        val created = repository.startActivity(session, description, category)
        _state.value = _state.value.copy(
            activities = listOf(created) + _state.value.activities,
            message = "Activiteit gestart",
        )
    }

    fun stopActiveActivity() = launchBusy {
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
    ) = launchBusy {
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
    ) = launchBusy {
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

    fun renameProfile(vaultId: String, label: String) {
        val updated = repository.renameSession(vaultId, label)
        _state.value = _state.value.copy(
            sessions = _state.value.sessions.map {
                if (it.vaultId == vaultId) updated else it
            },
            message = "Profielnaam gewijzigd",
        )
    }

    fun saveCategory(
        categoryId: String?,
        label: String,
        pointsPer30Minutes: Double,
    ) {
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
        updateSessionCategories(session, categories)
        _state.value = _state.value.copy(
            message = if (categoryId == null) "Categorie toegevoegd" else "Categorie gewijzigd",
        )
    }

    fun deleteCategory(categoryId: String) {
        val session = requireNotNull(_state.value.selectedSession)
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        check(session.categories.size > 1) { "Er moet minimaal één categorie overblijven" }

        val categories = session.categories.filterNot { it.id == categoryId }
        check(categories.size != session.categories.size) { "Categorie niet gevonden" }

        updateSessionCategories(session, categories)
        _state.value = _state.value.copy(message = "Categorie verwijderd")
    }

    fun restoreDefaultCategories() {
        val session = requireNotNull(_state.value.selectedSession)
        check(session.access == AccessMode.RW) { "Deze koppeling is alleen-lezen" }
        updateSessionCategories(session, ActivityCategory.defaults)
        _state.value = _state.value.copy(message = "Standaardcategorieën hersteld")
    }

    fun deleteActivity(item: ActivityItem) = launchBusy {
        val session = requireNotNull(_state.value.selectedSession)
        repository.deleteActivity(session, item)
        _state.value = _state.value.copy(
            activities = _state.value.activities.filterNot { it.recordId == item.recordId },
            message = "Activiteit verwijderd",
        )
    }

    fun exportExcel() = launchBusy {
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

    fun importExcel(fallbackYear: Int) = launchBusy {
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
        if (selected != null) syncCurrent()
    }

    fun checkHealth() {
        scope.launch {
            runCatching { api.health() }
                .onSuccess { _state.value = _state.value.copy(health = "Online") }
                .onFailure { _state.value = _state.value.copy(health = "Niet bereikbaar") }
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun updateSessionCategories(
        session: VaultSession,
        categories: List<ActivityCategory>,
    ) {
        val updated = repository.updateCategories(session.vaultId, categories)
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

    private fun launchBusy(block: suspend () -> Unit) {
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { block() }
                .onFailure { e -> _state.value = _state.value.copy(error = e.message ?: e::class.simpleName) }
            _state.value = _state.value.copy(busy = false)
        }
    }
}
