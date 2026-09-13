// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import net.dikkenberg.activiteitenweger.AppController
import net.dikkenberg.activiteitenweger.AppUiState
import net.dikkenberg.activiteitenweger.model.ActivityCategory
import net.dikkenberg.activiteitenweger.model.ActivityItem
import net.dikkenberg.activiteitenweger.platform.appBuildNumber
import net.dikkenberg.activiteitenweger.platform.appVersionName
import kotlin.math.abs
import kotlin.time.Clock

private enum class Destination(val label: String) {
    TODAY("Vandaag"),
    HISTORY("Historie"),
    CLIENTS("Profielen"),
    SHARE("Delen"),
    SETTINGS("Instellingen"),
}

@Composable
fun ActiviteitenwegerApp(controller: AppController = remember { AppController() }) {
    val state by controller.state.collectAsState()
    var destination by remember { mutableStateOf(Destination.TODAY) }

    LaunchedEffect(Unit) { controller.initialize() }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when {
                !state.initialized -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.sessions.isEmpty() -> WelcomeScreen(
                    busy = state.busy,
                    error = state.error,
                    onCreate = controller::createVault,
                )
                else -> AdaptiveShell(
                    destination = destination,
                    onDestination = { destination = it },
                    state = state,
                    controller = controller,
                )
            }
        }
    }
}

@Composable
private fun AdaptiveShell(
    destination: Destination,
    onDestination: (Destination) -> Unit,
    state: AppUiState,
    controller: AppController,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Spacer(Modifier.height(16.dp))
                    Destination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item,
                            onClick = { onDestination(item) },
                            icon = { Text(item.label.take(1)) },
                            label = { Text(item.label) },
                        )
                    }
                }
                VerticalDivider()
                Content(destination, state, controller, Modifier.weight(1f))
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { onDestination(item) },
                                icon = { Text(item.label.take(1)) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            ) { padding ->
                Content(destination, state, controller, Modifier.padding(padding))
            }
        }
    }

    state.error?.let { MessageDialog("Fout", it, controller::clearNotice) }
    state.message?.let { MessageDialog("Activiteitenweger", it, controller::clearNotice) }
}

@Composable
private fun Content(
    destination: Destination,
    state: AppUiState,
    controller: AppController,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (destination) {
            Destination.TODAY -> TodayScreen(state, controller)
            Destination.HISTORY -> HistoryScreen(state, controller)
            Destination.CLIENTS -> ProfilesScreen(state, controller)
            Destination.SHARE -> ShareScreen(state)
            Destination.SETTINGS -> SettingsScreen(state, controller)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
private fun WelcomeScreen(busy: Boolean, error: String?, onCreate: (String) -> Unit) {
    var label by remember { mutableStateOf("Mijn Activiteitenweger") }
    var showLicense by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Activiteitenweger", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "Registreer activiteiten en deel later versleuteld met een behandelaar. " +
                "De server ontvangt geen leesbare activiteitgegevens.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(label, { label = it }, label = { Text("Naam van profiel") })
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onCreate(label) }, enabled = !busy) {
            Text("Nieuwe Activiteitenweger maken")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
        Text(
            "Versie ${appVersionName()} (build ${appBuildNumber()})",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Copyright © 2026 Bas van den Dikkenberg · GNU GPL v3.0",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { showLicense = true }) { Text("Licentie-informatie") }
    }

    if (showLicense) {
        LicenseDialog(onDismiss = { showLicense = false })
    }
}

@Composable
private fun TodayScreen(state: AppUiState, controller: AppController) {
    var showStart by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ActivityItem?>(null) }
    val now = rememberTicker()
    val completed = state.activities.filter { it.payload.endedAt != null }
    val today = completed.filter { it.payload.localDate() == localToday() }
    val score = today.sumOf { it.payload.points() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(state.selectedSession?.label ?: "Vandaag", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Dagtotaal", style = MaterialTheme.typography.labelLarge)
                    Text(formatPoints(score), style = MaterialTheme.typography.headlineLarge)
                }
                Text("${today.size} afgerond", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))

        state.activeActivity?.let { active ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(active.payload.description, style = MaterialTheme.typography.titleLarge)
                    Text(active.payload.category.label)
                    Text("Gestart om ${formatLocalTime(active.payload.startedAt)}")
                    Text(formatDuration(active.payload.durationSeconds(now)), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = controller::stopActiveActivity,
                        enabled = state.canWrite && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Klaar met activiteit") }
                }
            }
        } ?: run {
            Button(
                onClick = { showStart = true },
                enabled = state.canWrite && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start activiteit") }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showManual = true },
            enabled = state.canWrite && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Activiteit handmatig invoeren") }

        if (!state.canWrite) {
            Spacer(Modifier.height(8.dp))
            Text("Dit profiel is alleen-lezen (R).", color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        Text("Activiteiten", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.activities.size) { index ->
                val item = state.activities[index]
                ActivityCard(
                    item = item,
                    now = now,
                    canEdit = state.canWrite && item.payload.endedAt != null,
                    canDelete = state.canWrite && item.payload.endedAt != null,
                    onEdit = { editingItem = item },
                    onDelete = { controller.deleteActivity(item) },
                )
            }
        }
    }

    if (showStart) {
        StartActivityDialog(
            onDismiss = { showStart = false },
            onStart = { description, category ->
                showStart = false
                controller.startActivity(description, category)
            }
        )
    }

    if (showManual) {
        ActivityEditorDialog(
            title = "Activiteit handmatig invoeren",
            initialDescription = "",
            initialCategory = ActivityCategory.LIGHT,
            initialStartDate = localToday(),
            initialStartTime = formatLocalTime(Clock.System.now().toString()),
            initialEndDate = localToday(),
            initialEndTime = formatLocalTime(Clock.System.now().toString()),
            confirmLabel = "Toevoegen",
            onDismiss = { showManual = false },
            onSave = { description, category, startDate, startTime, endDate, endTime ->
                showManual = false
                controller.addManualActivity(description, category, startDate, startTime, endDate, endTime)
            },
        )
    }

    editingItem?.let { item ->
        ActivityEditorDialog(
            title = "Activiteit wijzigen",
            initialDescription = item.payload.description,
            initialCategory = item.payload.category,
            initialStartDate = formatLocalDate(item.payload.startedAt),
            initialStartTime = formatLocalTime(item.payload.startedAt),
            initialEndDate = formatLocalDate(requireNotNull(item.payload.endedAt)),
            initialEndTime = formatLocalTime(requireNotNull(item.payload.endedAt)),
            confirmLabel = "Opslaan",
            onDismiss = { editingItem = null },
            onSave = { description, category, startDate, startTime, endDate, endTime ->
                editingItem = null
                controller.updateActivity(item, description, category, startDate, startTime, endDate, endTime)
            },
        )
    }
}

@Composable
private fun ActivityCard(
    item: ActivityItem,
    now: kotlin.time.Instant,
    canEdit: Boolean,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.payload.description, style = MaterialTheme.typography.titleMedium)
                Text(timeRange(item))
                Text("${item.payload.category.label} · ${formatDuration(item.payload.durationSeconds(now))}")
                Text("${formatPoints(item.payload.points(now))} punten", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (canEdit) {
                    TextButton(onClick = onEdit) { Text("Wijzig") }
                }
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text("Verwijder") }
                }
            }
        }
    }
}

@Composable
private fun StartActivityDialog(onDismiss: () -> Unit, onStart: (String, ActivityCategory) -> Unit) {
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ActivityCategory.LIGHT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start activiteit") },
        text = {
            Column {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Activiteit") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                ActivityCategory.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = category == option, onClick = { category = option })
                        Text("${option.label} (${signed(option.pointsPer30Minutes)} per 30 min)")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(description, category) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleer") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityEditorDialog(
    title: String,
    initialDescription: String,
    initialCategory: ActivityCategory,
    initialStartDate: String,
    initialStartTime: String,
    initialEndDate: String,
    initialEndTime: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onSave: (String, ActivityCategory, String, String, String, String) -> Unit,
) {
    var description by remember { mutableStateOf(initialDescription) }
    var category by remember { mutableStateOf(initialCategory) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var startTime by remember { mutableStateOf(initialStartTime) }
    var endDate by remember { mutableStateOf(initialEndDate) }
    var endTime by remember { mutableStateOf(initialEndTime) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Activiteit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Text("Start", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTimePickerButton(
                        label = "Datum",
                        value = formatIsoDateForDisplay(startDate),
                        onClick = { showStartDatePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                    DateTimePickerButton(
                        label = "Tijd",
                        value = startTime,
                        onClick = { showStartTimePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Einde", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTimePickerButton(
                        label = "Datum",
                        value = formatIsoDateForDisplay(endDate),
                        onClick = { showEndDatePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                    DateTimePickerButton(
                        label = "Tijd",
                        value = endTime,
                        onClick = { showEndTimePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(12.dp))
                ActivityCategory.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = category == option, onClick = { category = option })
                        Text("${option.label} (${signed(option.pointsPer30Minutes)} per 30 min)")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(description, category, startDate, startTime, endDate, endTime)
                }
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleer") } },
    )

    if (showStartDatePicker) {
        DateChooserDialog(
            title = "Startdatum",
            initialDate = startDate,
            onDismiss = { showStartDatePicker = false },
            onSelected = {
                startDate = it
                showStartDatePicker = false
            },
        )
    }

    if (showStartTimePicker) {
        TimeChooserDialog(
            title = "Starttijd",
            initialTime = startTime,
            onDismiss = { showStartTimePicker = false },
            onSelected = {
                startTime = it
                showStartTimePicker = false
            },
        )
    }

    if (showEndDatePicker) {
        DateChooserDialog(
            title = "Einddatum",
            initialDate = endDate,
            onDismiss = { showEndDatePicker = false },
            onSelected = {
                endDate = it
                showEndDatePicker = false
            },
        )
    }

    if (showEndTimePicker) {
        TimeChooserDialog(
            title = "Eindtijd",
            initialTime = endTime,
            onDismiss = { showEndTimePicker = false },
            onSelected = {
                endTime = it
                showEndTimePicker = false
            },
        )
    }
}

@Composable
private fun DateTimePickerButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChooserDialog(
    title: String,
    initialDate: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = isoDateToUtcMillis(initialDate),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { onSelected(utcMillisToIsoDate(it)) }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleer") }
        },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    title,
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChooserDialog(
    title: String,
    initialTime: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val (hour, minute) = parseHourMinute(initialTime)
    val state = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelected(
                        "${state.hour.toString().padStart(2, '0')}:" +
                            state.minute.toString().padStart(2, '0')
                    )
                }
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleer") }
        },
    )
}

@Composable
private fun HistoryScreen(state: AppUiState, controller: AppController) {
    var editingItem by remember { mutableStateOf<ActivityItem?>(null) }
    val groups = state.activities
        .filter { it.payload.endedAt != null }
        .groupBy { it.payload.localDate() }
        .entries
        .sortedByDescending { it.key }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { (date, items) ->
            item {
                Text(
                    "$date · ${formatPoints(items.sumOf { it.payload.points() })} punten",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(items.size) { index ->
                val activity = items[index]
                ActivityCard(
                    item = activity,
                    now = Clock.System.now(),
                    canEdit = state.canWrite,
                    canDelete = state.canWrite,
                    onEdit = { editingItem = activity },
                    onDelete = { controller.deleteActivity(activity) },
                )
            }
        }
    }

    editingItem?.let { item ->
        ActivityEditorDialog(
            title = "Activiteit wijzigen",
            initialDescription = item.payload.description,
            initialCategory = item.payload.category,
            initialStartDate = formatLocalDate(item.payload.startedAt),
            initialStartTime = formatLocalTime(item.payload.startedAt),
            initialEndDate = formatLocalDate(requireNotNull(item.payload.endedAt)),
            initialEndTime = formatLocalTime(requireNotNull(item.payload.endedAt)),
            confirmLabel = "Opslaan",
            onDismiss = { editingItem = null },
            onSave = { description, category, startDate, startTime, endDate, endTime ->
                editingItem = null
                controller.updateActivity(item, description, category, startDate, startTime, endDate, endTime)
            },
        )
    }
}

@Composable
private fun ProfilesScreen(state: AppUiState, controller: AppController) {
    var newProfile by remember { mutableStateOf(false) }
    var editingVaultId by remember { mutableStateOf<String?>(null) }
    var editingLabel by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Profielen / cliënten", style = MaterialTheme.typography.headlineMedium)
        Text("Eén app-installatie kan meerdere versleutelde vaults beheren.")
        Spacer(Modifier.height(12.dp))
        state.sessions.forEach { session ->
            ElevatedCard(
                onClick = { controller.selectVault(session.vaultId) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(session.label, style = MaterialTheme.typography.titleMedium)
                        Text(if (session.owner) "Eigen profiel" else "Gekoppeld profiel")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(session.access.name)
                        TextButton(
                            onClick = {
                                editingVaultId = session.vaultId
                                editingLabel = session.label
                            }
                        ) { Text("Naam wijzigen") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { newProfile = true }) { Text("Nieuw eigen profiel") }
    }
    editingVaultId?.let { vaultId ->
        AlertDialog(
            onDismissRequest = { editingVaultId = null },
            title = { Text("Profielnaam wijzigen") },
            text = {
                OutlinedTextField(
                    value = editingLabel,
                    onValueChange = { editingLabel = it },
                    label = { Text("Naam") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        controller.renameProfile(vaultId, editingLabel)
                        editingVaultId = null
                    }
                ) { Text("Opslaan") }
            },
            dismissButton = {
                TextButton(onClick = { editingVaultId = null }) { Text("Annuleer") }
            },
        )
    }

    if (newProfile) {
        var label by remember { mutableStateOf("Nieuw profiel") }
        AlertDialog(
            onDismissRequest = { newProfile = false },
            title = { Text("Nieuw profiel") },
            text = { OutlinedTextField(label, { label = it }, label = { Text("Naam") }) },
            confirmButton = {
                Button(onClick = { newProfile = false; controller.createVault(label) }) { Text("Aanmaken") }
            },
            dismissButton = { TextButton(onClick = { newProfile = false }) { Text("Annuleer") } },
        )
    }
}

@Composable
private fun ShareScreen(state: AppUiState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Delen en koppelen", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("R — Alleen lezen", style = MaterialTheme.typography.titleMedium)
                Text("Voor bijvoorbeeld een behandelaar die gegevens mag bekijken maar niet wijzigen.")
                Spacer(Modifier.height(12.dp))
                Text("RW — Lezen en schrijven", style = MaterialTheme.typography.titleMedium)
                Text("Voor een eigen extra apparaat of iemand die ook registraties mag aanpassen.")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "De app-architectuur is voorbereid op QR/koppelcode en meerdere cliënten. " +
                "De huidige server-API heeft nog geen pairing-relay/device-grant endpoints; daarom is koppelen in deze build bewust nog niet activeerbaar.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text("Vault: ${state.selectedSession?.vaultId ?: "-"}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, controller: AppController) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Instellingen", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("Server: ${state.health}")
        Text("https://app.dikkenberg.net")
        Spacer(Modifier.height(16.dp))
        Text("App", style = MaterialTheme.typography.titleMedium)
        Text("Versie ${appVersionName()} (build ${appBuildNumber()})")
        Text("Copyright © 2026 Bas van den Dikkenberg")
        Text("Licentie: GNU General Public License v3.0")
        Text("Broncode: github.com/basd82/Activiteitenweger")
        TextButton(onClick = { showLicense = true }) { Text("Licentie-informatie") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = controller::syncCurrent, enabled = !state.busy) { Text("Nu synchroniseren") }
        Spacer(Modifier.height(20.dp))
        Text("Gebruik", style = MaterialTheme.typography.titleMedium)
        Text(
            "De Activiteitenweger is een hulpmiddel voor registratie en inzicht. Scores zijn geen medische beoordeling " +
                "en vervangen geen advies, diagnose of behandeling door een gekwalificeerde professional."
        )
        Spacer(Modifier.height(24.dp))
        if (state.selectedSession?.owner == true) {
            OutlinedButton(onClick = { confirmDelete = true }, enabled = !state.busy) {
                Text("Deze Activiteitenweger volledig verwijderen")
            }
        }
    }
    if (showLicense) {
        LicenseDialog(onDismiss = { showLicense = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Alles verwijderen?") },
            text = { Text("Alle versleutelde servergegevens van dit profiel worden definitief verwijderd. Dit kan niet ongedaan worden gemaakt.") },
            confirmButton = {
                Button(onClick = { confirmDelete = false; controller.deleteCurrentVault() }) { Text("Verwijderen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuleer") } },
        )
    }
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GNU GPL v3.0") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Copyright © 2026 Bas van den Dikkenberg.\n\n" +
                            "Activiteitenweger is vrije software. Je mag deze software gebruiken, kopiëren, " +
                            "wijzigen en verspreiden onder de voorwaarden van de GNU General Public License " +
                            "versie 3.0.\n\n" +
                            "Deze software wordt geleverd zonder enige garantie, voor zover wettelijk toegestaan.\n\n" +
                            "Broncode: https://github.com/basd82/Activiteitenweger"
                    )
                }
                item {
                    HorizontalDivider()
                }
                item {
                    Text(GPL_V3_LICENSE_TEXT, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Sluiten") }
        },
    )
}

@Composable
private fun MessageDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun rememberTicker(): kotlin.time.Instant {
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Clock.System.now()
        }
    }
    return now
}

private fun localToday(): String =
    Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()


private fun timeRange(item: ActivityItem): String {
    val start = formatLocalTime(item.payload.startedAt)
    val end = item.payload.endedAt?.let(::formatLocalTime)
    return if (end == null) "Vanaf $start" else "$start – $end"
}

private fun formatIsoDateForDisplay(value: String): String {
    val date = LocalDate.parse(value)
    return "${date.day.toString().padStart(2, '0')}-" +
        "${date.monthNumber.toString().padStart(2, '0')}-${date.year}"
}

private fun isoDateToUtcMillis(value: String): Long =
    LocalDate.parse(value)
        .atStartOfDayIn(TimeZone.UTC)
        .toEpochMilliseconds()

private fun utcMillisToIsoDate(value: Long): String =
    kotlin.time.Instant.fromEpochMilliseconds(value)
        .toLocalDateTime(TimeZone.UTC)
        .date
        .toString()

private fun parseHourMinute(value: String): Pair<Int, Int> {
    val parts = value.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hour to minute
}

private fun formatLocalDate(value: String): String =
    kotlin.time.Instant.parse(value)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

private fun formatLocalTime(value: String): String {
    val time = kotlin.time.Instant.parse(value)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .time
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}u ${m.toString().padStart(2, '0')}m" else "${m}m ${s.toString().padStart(2, '0')}s"
}

private fun formatPoints(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (abs(rounded - rounded.toInt()) < 0.0001) rounded.toInt().toString() else rounded.toString()
}

private fun signed(value: Double): String = if (value > 0) "+${value.toInt()}" else value.toInt().toString()
