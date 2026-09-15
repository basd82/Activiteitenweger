// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import net.dikkenberg.activiteitenweger.AppController
import net.dikkenberg.activiteitenweger.AppUiState
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import net.dikkenberg.activiteitenweger.model.AccessMode
import net.dikkenberg.activiteitenweger.model.ActivityCategory
import net.dikkenberg.activiteitenweger.model.ActivityItem
import net.dikkenberg.activiteitenweger.model.ActivityPreset
import net.dikkenberg.activiteitenweger.model.SyncStatus
import net.dikkenberg.activiteitenweger.platform.CameraPermissionGate
import net.dikkenberg.activiteitenweger.platform.appBuildNumber
import net.dikkenberg.activiteitenweger.platform.appVersionName
import net.dikkenberg.activiteitenweger.platform.copyTextToClipboard
import net.dikkenberg.activiteitenweger.platform.passwordManagerSaveAvailable
import net.dikkenberg.activiteitenweger.platform.shareText
import kotlin.math.abs
import org.ncgroup.kscan.BarcodeFormat
import org.ncgroup.kscan.BarcodeResult
import org.ncgroup.kscan.ScannerView
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

    LaunchedEffect(state.initialized, state.selectedVaultId) {
        if (state.initialized && state.selectedVaultId != null) {
            while (true) {
                delay(30_000)
                controller.syncCurrentSilently()
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when {
                !state.initialized -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.appLocked -> AppLockScreen(state, controller)
                state.sessions.isEmpty() -> WelcomeScreen(
                    busy = state.busy,
                    error = state.error,
                    onCreate = controller::createVault,
                    onJoin = controller::claimPairing,
                    onRecover = controller::claimRecovery,
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

    state.conflictSnapshot?.let { conflict ->
        ConflictResolverDialog(
            conflict = conflict,
            onUseMine = controller::resolveConflictKeepMine,
            onUseServer = controller::resolveConflictUseServer,
        )
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
            Destination.SHARE -> ShareScreen(state, controller)
            Destination.SETTINGS -> SettingsScreen(state, controller)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
private fun WelcomeScreen(
    busy: Boolean,
    error: String?,
    onCreate: (String) -> Unit,
    onJoin: (String) -> Unit,
    onRecover: (String) -> Unit,
) {
    var label by remember { mutableStateOf("Mijn Activiteitenweger") }
    var showLicense by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Activiteitenweger", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "Registreer activiteiten of koppel veilig een bestaand profiel via QR-code of koppelcode.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(label, { label = it }, label = { Text("Naam van profiel") })
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onCreate(label) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Nieuwe Activiteitenweger maken")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showJoin = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Bestaand profiel koppelen")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showRecovery = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Profiel herstellen met herstelcode")
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
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

    if (showJoin) {
        JoinPairingDialog(
            onDismiss = { showJoin = false },
            onJoin = {
                showJoin = false
                onJoin(it)
            },
        )
    }

    if (showRecovery) {
        RecoveryCodeEntryDialog(
            onDismiss = { showRecovery = false },
            onRecover = {
                showRecovery = false
                onRecover(it)
            },
        )
    }

    if (showLicense) {
        LicenseDialog(onDismiss = { showLicense = false })
    }
}

@Composable
private fun AppLockScreen(state: AppUiState, controller: AppController) {
    var pin by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Activiteitenweger", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Deze app is vergrendeld.")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { value -> pin = value.filter(Char::isDigit).take(12) },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                controller.unlockWithPin(pin)
                pin = ""
            },
            enabled = pin.length >= 4,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ontgrendelen")
        }
        if (state.biometricsEnabled && state.biometricName != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = controller::unlockWithBiometrics,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Ontgrendelen met ${state.biometricName}")
            }
        }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TodayScreen(state: AppUiState, controller: AppController) {
    var showStart by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ActivityItem?>(null) }
    val now = rememberTicker()
    val todayKey = localToday()
    val completed = state.activities.filter { it.payload.endedAt != null }
    val today = completed.filter { it.payload.localDate() == todayKey }
    val todayActivities = state.activities.filter { it.payload.localDate() == todayKey }
    val score = today.sumOf { it.payload.points() }
    val activityListState = androidx.compose.foundation.lazy.rememberLazyListState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(state.selectedSession?.label ?: "Vandaag", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        val target = state.selectedSession?.dailyPointTarget ?: 17.5
        val orangeAbove = state.selectedSession?.dailyPointOrangeAbove ?: 0.0
        val redAbove = state.selectedSession?.dailyPointRedAbove ?: 5.0
        val difference = score - target
        val targetColor = when {
            difference <= orangeAbove -> Color(0xFF2E7D32)
            difference <= redAbove -> Color(0xFFF57C00)
            else -> MaterialTheme.colorScheme.error
        }
        val targetText = when {
            difference <= 0.0 -> "Binnen streefwaarde"
            difference <= 5.0 -> "${formatPoints(difference)} boven streefwaarde"
            else -> "${formatPoints(difference)} boven streefwaarde"
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Dagtotaal", style = MaterialTheme.typography.labelLarge)
                    Text(
                        formatPoints(score),
                        style = MaterialTheme.typography.headlineLarge,
                        color = targetColor,
                    )
                    Text(
                        "Streefwaarde: ${formatPoints(target)} punten",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        targetText,
                        style = MaterialTheme.typography.labelMedium,
                        color = targetColor,
                    )
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
                    SyncStatusText(active.syncStatus)
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Activiteiten vandaag", style = MaterialTheme.typography.titleMedium)
            LazyListScrollButtons(
                state = activityListState,
                itemCount = todayActivities.size,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (todayActivities.isEmpty()) {
            Text(
                "Vandaag zijn nog geen activiteiten geregistreerd.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                state = activityListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(todayActivities.size) { index ->
                    val item = todayActivities[index]
                    ActivityCard(
                        item = item,
                        now = now,
                        canEdit = state.canWrite && item.payload.endedAt != null && item.syncStatus != SyncStatus.CONFLICT,
                        canDelete = state.canWrite && item.payload.endedAt != null && item.syncStatus != SyncStatus.CONFLICT,
                        onEdit = { editingItem = item },
                        onDelete = { controller.deleteActivity(item) },
                    )
                }
            }
        }
    }

    if (showStart) {
        StartActivityDialog(
            categories = state.categories,
            presets = state.activityPresets,
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
            categories = state.categories,
            presets = state.activityPresets,
            initialCategory = state.categories.firstOrNull { it.id == ActivityCategory.LIGHT.id }
                ?: state.categories.firstOrNull()
                ?: ActivityCategory.LIGHT,
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
            categories = state.categories,
            presets = state.activityPresets,
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
                SyncStatusText(item.syncStatus)
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
private fun SyncStatusText(status: SyncStatus) {
    when (status) {
        SyncStatus.SYNCED -> Unit
        SyncStatus.PENDING -> Text(
            "Wacht op synchronisatie",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        SyncStatus.CONFLICT -> Text(
            "Synchronisatieconflict",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StartActivityDialog(
    categories: List<ActivityCategory>,
    presets: List<ActivityPreset>,
    onDismiss: () -> Unit,
    onStart: (String, ActivityCategory) -> Unit,
) {
    val availableCategories = categories.ifEmpty { ActivityCategory.defaults }
    var description by remember { mutableStateOf("") }
    var category by remember(availableCategories) {
        mutableStateOf(
            availableCategories.firstOrNull { it.id == ActivityCategory.LIGHT.id }
                ?: availableCategories.first()
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start activiteit") },
        text = {
            Column {
                if (presets.isNotEmpty()) {
                    ActivityPresetDropdown(
                        presets = presets,
                        categories = availableCategories,
                        onSelected = { preset, presetCategory ->
                            description = preset.label
                            category = presetCategory
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Activiteit") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                availableCategories.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = category.id == option.id, onClick = { category = option })
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
    categories: List<ActivityCategory>,
    presets: List<ActivityPreset>,
    initialCategory: ActivityCategory,
    initialStartDate: String,
    initialStartTime: String,
    initialEndDate: String,
    initialEndTime: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onSave: (String, ActivityCategory, String, String, String, String) -> Unit,
) {
    val availableCategories = (categories + initialCategory).distinctBy { it.id }
    var description by remember { mutableStateOf(initialDescription) }
    var category by remember { mutableStateOf(initialCategory) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var startTime by remember { mutableStateOf(initialStartTime) }
    var endDate by remember { mutableStateOf(initialEndDate) }
    var endTime by remember { mutableStateOf(initialEndTime) }
    var endDateManuallyChanged by remember { mutableStateOf(false) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (presets.isNotEmpty()) {
                    ActivityPresetDropdown(
                        presets = presets,
                        categories = availableCategories,
                        onSelected = { preset, presetCategory ->
                            description = preset.label
                            category = presetCategory
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
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
                availableCategories.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = category.id == option.id, onClick = { category = option })
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
                val previousStartDate = startDate
                startDate = it
                if (!endDateManuallyChanged && endDate == previousStartDate) {
                    endDate = it
                }
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
                endDateManuallyChanged = true
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
    var selectedDate by remember(state.selectedVaultId) { mutableStateOf<String?>(null) }

    val groups = state.activities
        .filter { it.payload.endedAt != null }
        .groupBy { it.payload.localDate() }
        .entries
        .sortedByDescending { it.key }

    val effectiveSelectedDate = selectedDate
        ?.takeIf { selected -> groups.any { it.key == selected } }
    val historyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val selectedGroup = groups.firstOrNull { it.key == effectiveSelectedDate }
    val historyItemCount =
        1 +
            (if (groups.isEmpty()) 1 else 0) +
            groups.size +
            (selectedGroup?.let { 2 + it.value.size } ?: 0)

    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = historyListState,
            modifier = Modifier.fillMaxSize().padding(16.dp).padding(end = 56.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        item {
            Text("Historie", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kies een dag om de activiteiten en de eindstand van die dag te bekijken.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (groups.isEmpty()) {
            item {
                Text("Er zijn nog geen afgeronde activiteiten.")
            }
        }

        groups.forEach { (date, dayItems) ->
            val selected = date == effectiveSelectedDate
            item {
                ElevatedCard(
                    onClick = { selectedDate = if (selected) null else date },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "${dutchWeekday(date)} · ${formatIsoDateForDisplay(date)}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${dayItems.size} ${if (dayItems.size == 1) "activiteit" else "activiteiten"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (selected) {
                                Text("Geselecteerd", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        if (selected) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Text("Eindstand", style = MaterialTheme.typography.labelLarge)
                            Text(
                                formatPoints(dayItems.sumOf { it.payload.points() }),
                                style = MaterialTheme.typography.headlineLarge,
                            )
                        }
                    }
                }
            }

            if (selected) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Activiteiten op ${dutchWeekday(date).lowercase()} ${formatIsoDateForDisplay(date)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(dayItems.size) { index ->
                    val activity = dayItems[index]
                    ActivityCard(
                        item = activity,
                        now = Clock.System.now(),
                        canEdit = state.canWrite && activity.syncStatus != SyncStatus.CONFLICT,
                        canDelete = state.canWrite && activity.syncStatus != SyncStatus.CONFLICT,
                        onEdit = { editingItem = activity },
                        onDelete = { controller.deleteActivity(activity) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        }

        LazyListScrollButtons(
            state = historyListState,
            itemCount = historyItemCount,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            vertical = true,
        )
    }

    editingItem?.let { item ->
        ActivityEditorDialog(
            title = "Activiteit wijzigen",
            initialDescription = item.payload.description,
            categories = state.categories,
            presets = state.activityPresets,
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
    val profilesScrollState = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(profilesScrollState)
                .padding(16.dp)
                .padding(end = 56.dp, bottom = 72.dp)
        ) {
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

        ScrollStateButtons(
            state = profilesScrollState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
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
private fun ShareScreen(state: AppUiState, controller: AppController) {
    val session = state.selectedSession
    var showJoin by remember { mutableStateOf(false) }
    var revokeDeviceId by remember { mutableStateOf<String?>(null) }
    var transferOwnerDeviceId by remember { mutableStateOf<String?>(null) }
    var confirmSelfRevoke by remember { mutableStateOf(false) }
    var showRevokedDevices by remember { mutableStateOf(false) }
    val currentDevice = state.devices.firstOrNull { it.deviceId == session?.deviceId }
    var currentDeviceName by remember(state.selectedVaultId, currentDevice?.name) {
        mutableStateOf(currentDevice?.name.orEmpty())
    }
    val scrollState = rememberScrollState()

    LaunchedEffect(state.selectedVaultId, session?.access) {
        if (session != null && session.access == AccessMode.RW) {
            controller.refreshDevices()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Delen en koppelen", style = MaterialTheme.typography.headlineMedium)

        if (session == null) {
            Text("Kies eerst een profiel.")
            return@Column
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Toegang delen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("R — alleen lezen: geschikt voor iemand die activiteiten alleen hoeft te bekijken.")
                Text("RW — lezen en schrijven: geschikt voor een eigen extra apparaat of iemand die ook mag registreren en wijzigen.")
                if (session.owner) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { controller.createPairingInvitation(AccessMode.R) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Koppel apparaat met R-toegang") }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { controller.createPairingInvitation(AccessMode.RW) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Koppel apparaat met RW-toegang") }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text("Alleen de eigenaar van dit profiel kan nieuwe apparaten koppelen.")
                }
            }
        }

        if (session.owner) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Herstelbackup", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Met een herstelcode kun je dit profiel terugzetten als je alle gekoppelde apparaten kwijtraakt.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val activeDeviceCount = state.devices.count { it.status == "ACTIVE" }
                    if (activeDeviceCount <= 1 && state.recoveryId == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Let op: dit is nu je enige actieve apparaat. Zonder herstelbackup kan verlies van dit apparaat betekenen dat je versleutelde gegevens niet meer toegankelijk zijn.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.recoveryId == null) {
                        Button(
                            onClick = controller::createRecoveryCredential,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Herstelbackup maken")
                        }
                    } else {
                        Text(
                            "Herstelbackup actief" +
                                (state.recoveryCreatedAt?.let { " · gemaakt " + formatLocalDateTime(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = controller::createRecoveryCredential,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Nieuwe herstelbackup maken")
                        }
                        TextButton(
                            onClick = controller::revokeRecoveryCredential,
                            enabled = !state.busy,
                        ) {
                            Text("Herstelbackup intrekken")
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { showJoin = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Bestaand profiel aan deze app koppelen")
        }

        if (session.access == AccessMode.RW) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Apparaten", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = controller::refreshDevices, enabled = !state.busy) {
                    Text("Vernieuwen")
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Naam van dit apparaat", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = currentDeviceName,
                        onValueChange = { currentDeviceName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Bijvoorbeeld: Bas iPhone") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { controller.updateDeviceName(currentDeviceName) },
                        enabled = !state.busy && currentDeviceName.trim().isNotEmpty(),
                    ) { Text("Apparaatnaam opslaan") }
                    Text(
                        "Deze naam wordt versleuteld opgeslagen en is alleen binnen dit profiel leesbaar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            val revokedCount = state.devices.count { it.status == "REVOKED" }
            val visibleDevices = state.devices.filter { showRevokedDevices || it.status != "REVOKED" }
            if (revokedCount > 0) {
                TextButton(onClick = { showRevokedDevices = !showRevokedDevices }) {
                    Text(
                        if (showRevokedDevices) "Ingetrokken apparaten verbergen"
                        else "Ingetrokken apparaten tonen ($revokedCount)"
                    )
                }
            }

            if (visibleDevices.isEmpty()) {
                Text("Nog geen apparatenlijst geladen.")
            } else {
                visibleDevices.forEach { device ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        device.name ?: when {
                                            device.deviceId == session.deviceId -> "Dit apparaat"
                                            device.owner -> "Eigenaar"
                                            else -> "Gekoppeld apparaat"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    if (device.deviceId == session.deviceId) {
                                        Text("Dit apparaat", style = MaterialTheme.typography.bodySmall)
                                    } else if (device.owner) {
                                        Text("Eigenaar", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        "Toegang: " + when (device.access) {
                                            AccessMode.R -> "alleen lezen"
                                            AccessMode.RW -> "lezen en schrijven"
                                        }
                                    )
                                    Text(
                                        "Status: " + when (device.status) {
                                            "ACTIVE" -> "Actief"
                                            "REVOKED" -> "Ingetrokken"
                                            else -> device.status
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    device.lastSeenAt?.let {
                                        Text("Laatst actief: ${formatLocalTime(it)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (
                                    session.owner &&
                                    !device.owner &&
                                    device.status == "ACTIVE" &&
                                    device.deviceId != session.deviceId
                                ) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (device.access == AccessMode.RW) {
                                            TextButton(onClick = { transferOwnerDeviceId = device.deviceId }) {
                                                Text("Maak eigenaar")
                                            }
                                        }
                                        TextButton(onClick = { revokeDeviceId = device.deviceId }) {
                                            Text("Intrekken")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!session.owner) {
            HorizontalDivider()
            OutlinedButton(
                onClick = { confirmSelfRevoke = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dit apparaat loskoppelen van profiel")
            }
        }

        Text("Vault: ${session.vaultId}", style = MaterialTheme.typography.bodySmall)
    }

    state.recoveryCredential?.let { recovery ->
        RecoveryCredentialDialog(
            recovery = recovery,
            onClose = controller::clearRecoveryCredential,
            onSaveToPasswordManager = controller::saveRecoveryCredentialToPasswordManager,
        )
    }

    state.pairingInvitation?.let { invitation ->
        PairingInvitationDialog(
            invitation = invitation,
            onClose = controller::clearPairingInvitation,
            onRevoke = controller::revokePairingInvitation,
        )
    }

    if (showJoin) {
        JoinPairingDialog(
            onDismiss = { showJoin = false },
            onJoin = { code ->
                showJoin = false
                controller.claimPairing(code)
            },
        )
    }

    transferOwnerDeviceId?.let { deviceId ->
        val device = state.devices.firstOrNull { it.deviceId == deviceId }
        AlertDialog(
            onDismissRequest = { transferOwnerDeviceId = null },
            title = { Text("Eigenaarschap overdragen?") },
            text = {
                Text(
                    "Na overdracht is " + (device?.name ?: "dit gekoppelde apparaat") +
                        " de eigenaar. Dit apparaat houdt RW-toegang, maar kan daarna niet meer " +
                        "koppelen, intrekken of verwijderen als eigenaar."
                )
            },
            confirmButton = {
                Button(onClick = {
                    transferOwnerDeviceId = null
                    controller.transferOwnership(deviceId)
                }) { Text("Overdragen") }
            },
            dismissButton = {
                TextButton(onClick = { transferOwnerDeviceId = null }) { Text("Annuleer") }
            },
        )
    }

    revokeDeviceId?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { revokeDeviceId = null },
            title = { Text("Toegang intrekken?") },
            text = { Text("Dit apparaat kan daarna niet meer synchroniseren met dit profiel.") },
            confirmButton = {
                Button(onClick = { revokeDeviceId = null; controller.revokeDevice(deviceId) }) {
                    Text("Intrekken")
                }
            },
            dismissButton = {
                TextButton(onClick = { revokeDeviceId = null }) { Text("Annuleer") }
            },
        )
    }

    if (confirmSelfRevoke) {
        AlertDialog(
            onDismissRequest = { confirmSelfRevoke = false },
            title = { Text("Profiel loskoppelen?") },
            text = { Text("De lokale toegang tot dit profiel wordt verwijderd. Andere profielen blijven staan.") },
            confirmButton = {
                Button(onClick = { confirmSelfRevoke = false; controller.selfRevokeCurrentProfile() }) {
                    Text("Loskoppelen")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSelfRevoke = false }) { Text("Annuleer") }
            },
        )
    }
}

@Composable
private fun AppSecuritySetupDialog(
    biometricName: String?,
    initialBiometrics: Boolean,
    initialTimeout: Long,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Long) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var useBiometrics by remember(initialBiometrics) {
        mutableStateOf(initialBiometrics && biometricName != null)
    }
    var timeout by remember(initialTimeout) { mutableStateOf(initialTimeout) }
    val pinValid = pin.length in 4..12 && pin.all(Char::isDigit) && pin == confirmPin

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App-beveiliging") },
        text = {
            Column {
                Text("Kies een PIN van 4 tot 12 cijfers. Deze PIN blijft alleen op dit apparaat.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text("Nieuwe PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter(Char::isDigit).take(12) },
                    label = { Text("PIN herhalen") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirmPin.isNotEmpty() && pin != confirmPin) {
                    Text("De PIN-codes zijn niet gelijk.", color = MaterialTheme.colorScheme.error)
                }
                if (biometricName != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = useBiometrics,
                            onCheckedChange = { useBiometrics = it },
                        )
                        Text("Ook ontgrendelen met " + biometricName)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Automatisch opnieuw vergrendelen", style = MaterialTheme.typography.labelLarge)
                listOf(
                    0L to "Direct",
                    60L to "Na 1 minuut",
                    300L to "Na 5 minuten",
                    900L to "Na 15 minuten",
                ).forEach { pair ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = timeout == pair.first,
                            onClick = { timeout = pair.first },
                        )
                        Text(pair.second)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(pin, useBiometrics, timeout) },
                enabled = pinValid,
            ) {
                Text("Opslaan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleer") }
        },
    )
}

@Composable
private fun AppSecurityDisableDialog(
    onDismiss: () -> Unit,
    onDisable: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App-beveiliging uitschakelen?") },
        text = {
            Column {
                Text("Voer je huidige PIN in om de lokale app-vergrendeling uit te schakelen.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                    label = { Text("Huidige PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDisable(pin) },
                enabled = pin.length >= 4,
            ) {
                Text("Uitschakelen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleer") }
        },
    )
}

@Composable
private fun SettingsScreen(state: AppUiState, controller: AppController) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showExcelImport by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<ActivityCategory?>(null) }
    var addingCategory by remember { mutableStateOf(false) }
    var deletingCategory by remember { mutableStateOf<ActivityCategory?>(null) }
    var addingPreset by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<ActivityPreset?>(null) }
    var deletingPreset by remember { mutableStateOf<ActivityPreset?>(null) }
    var showSecuritySetup by remember { mutableStateOf(false) }
    var showSecurityDisable by remember { mutableStateOf(false) }
    var targetPointsInput by remember(state.selectedVaultId, state.selectedSession?.dailyPointTarget) {
        mutableStateOf(
            (state.selectedSession?.dailyPointTarget ?: 17.5)
                .toString()
                .replace('.', ',')
        )
    }
    var orangeAboveInput by remember(state.selectedVaultId, state.selectedSession?.dailyPointOrangeAbove) {
        mutableStateOf(
            (state.selectedSession?.dailyPointOrangeAbove ?: 0.0)
                .toString()
                .replace('.', ',')
        )
    }
    var redAboveInput by remember(state.selectedVaultId, state.selectedSession?.dailyPointRedAbove) {
        mutableStateOf(
            (state.selectedSession?.dailyPointRedAbove ?: 5.0)
                .toString()
                .replace('.', ',')
        )
    }
    val settingsScrollState = rememberScrollState()
    var importYear by remember {
        mutableStateOf(
            Clock.System.now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .year
                .toString()
        )
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(settingsScrollState)
                .padding(16.dp)
                .padding(end = 56.dp, bottom = 72.dp)
        ) {
        Text("Instellingen", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            buildString {
                append("Server: ").append(state.health)
                state.serverVersion?.let { append(" · versie ").append(it) }
            }
        )
        Text("https://app.dikkenberg.net")
        when {
            state.syncing -> Text("Synchronisatie: bezig…")
            state.lastSyncAt != null -> Text("Laatste synchronisatie: ${formatLocalTime(state.lastSyncAt)}")
            else -> Text("Laatste synchronisatie: nog niet")
        }
        if (state.pendingChanges > 0) {
            Text("${state.pendingChanges} wijziging(en) wachten op synchronisatie")
        }
        if (state.conflictChanges > 0) {
            Text(
                "${state.conflictChanges} synchronisatieconflict(en)",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("App", style = MaterialTheme.typography.titleMedium)
        Text("Versie ${appVersionName()} (build ${appBuildNumber()})")
        Text("Copyright © 2026 Bas van den Dikkenberg")
        Text("Licentie: GNU General Public License v3.0")
        Text("Broncode: github.com/basd82/Activiteitenweger")
        TextButton(onClick = { showLicense = true }) { Text("Licentie-informatie") }
        Spacer(Modifier.height(20.dp))
        Text("Beveiliging", style = MaterialTheme.typography.titleMedium)
        if (state.appLockEnabled) {
            Text("App-vergrendeling staat aan.")
            Text(
                "Automatisch vergrendelen: " + formatLockTimeout(state.lockAfterSeconds),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (state.biometricsEnabled && state.biometricName != null) {
                    "Biometrie: " + state.biometricName
                } else {
                    "Biometrie: uit"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showSecuritySetup = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Wijzigen") }
                OutlinedButton(
                    onClick = { showSecurityDisable = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Uitschakelen") }
            }
        } else {
            Text(
                "Beveilig de app lokaal met een PIN en optioneel " + (state.biometricName ?: "biometrie") + ".",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showSecuritySetup = true },
                enabled = !state.busy,
            ) { Text("App-beveiliging instellen") }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = controller::syncCurrent, enabled = !state.busy) { Text("Nu synchroniseren") }
        Spacer(Modifier.height(20.dp))
        Text("Streefpunten per dag", style = MaterialTheme.typography.titleMedium)
        Text(
            "Stel het dagdoel en de kleurgrenzen in. De grenzen zijn het aantal punten " +
                "boven de streefwaarde waarop oranje en rood beginnen."
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = targetPointsInput,
            onValueChange = { targetPointsInput = it },
            label = { Text("Streefpunten") },
            singleLine = true,
            enabled = state.canWrite && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = orangeAboveInput,
                onValueChange = { orangeAboveInput = it },
                label = { Text("Oranje vanaf +") },
                singleLine = true,
                enabled = state.canWrite && !state.busy,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = redAboveInput,
                onValueChange = { redAboveInput = it },
                label = { Text("Rood vanaf +") },
                singleLine = true,
                enabled = state.canWrite && !state.busy,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        val targetValue = targetPointsInput.trim().replace(',', '.').toDoubleOrNull()
        val orangeValue = orangeAboveInput.trim().replace(',', '.').toDoubleOrNull()
        val redValue = redAboveInput.trim().replace(',', '.').toDoubleOrNull()
        Button(
            onClick = {
                if (targetValue != null && orangeValue != null && redValue != null) {
                    controller.saveDailyPointSettings(targetValue, orangeValue, redValue)
                }
            },
            enabled = state.canWrite &&
                !state.busy &&
                targetValue != null && targetValue >= 0.0 &&
                orangeValue != null && orangeValue >= 0.0 &&
                redValue != null && redValue >= orangeValue,
        ) {
            Text("Streefpunten en kleurgrenzen opslaan")
        }
        if (!state.canWrite) {
            Text(
                "De streefwaarde kan niet worden gewijzigd in een alleen-lezen profiel.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("Categorieën en punten", style = MaterialTheme.typography.titleMedium)
        Text(
            "De punten worden per 30 minuten ingesteld. Nieuwe profielen starten met " +
                "Ontspanning -1, Licht +1, Gemiddeld +2 en Zwaar +3."
        )
        Spacer(Modifier.height(8.dp))
        state.categories.forEach { category ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(category.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${signed(category.pointsPer30Minutes)} punten per 30 min",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(
                            onClick = { editingCategory = category },
                            enabled = state.canWrite && !state.busy,
                        ) {
                            Text("Wijzig")
                        }
                        TextButton(
                            onClick = { deletingCategory = category },
                            enabled = state.canWrite && !state.busy && state.categories.size > 1,
                        ) {
                            Text("Verwijder")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { addingCategory = true },
            enabled = state.canWrite && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Categorie toevoegen")
        }
        if (!state.canWrite) {
            Text(
                "Categorieën kunnen niet worden gewijzigd in een alleen-lezen profiel.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("Standaardactiviteiten", style = MaterialTheme.typography.titleMedium)
        Text(
            "Voeg veelgebruikte activiteiten toe. Bij het starten of handmatig invoeren " +
                "kun je ze daarna uit een keuzelijst selecteren."
        )
        Spacer(Modifier.height(8.dp))
        if (state.activityPresets.isEmpty()) {
            Text(
                "Nog geen standaardactiviteiten ingesteld.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            state.activityPresets.forEach { preset ->
                val presetCategory = state.categories.firstOrNull { it.id == preset.categoryId }
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                presetCategory?.let {
                                    "${it.label} · ${signed(it.pointsPer30Minutes)} per 30 min"
                                } ?: "Categorie niet beschikbaar",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(
                                onClick = { editingPreset = preset },
                                enabled = state.canWrite && !state.busy,
                            ) {
                                Text("Wijzig")
                            }
                            TextButton(
                                onClick = { deletingPreset = preset },
                                enabled = state.canWrite && !state.busy,
                            ) {
                                Text("Verwijder")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { addingPreset = true },
            enabled = state.canWrite && !state.busy && state.categories.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Standaardactiviteit toevoegen")
        }

        Spacer(Modifier.height(20.dp))
        Text("Excel import / export", style = MaterialTheme.typography.titleMedium)
        Text(
            "Exporteer het huidige profiel naar hetzelfde dagschema-formaat of importeer een bestaand .xlsx-bestand."
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = controller::exportExcel,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Excel exporteren")
            }
            OutlinedButton(
                onClick = { showExcelImport = true },
                enabled = !state.busy && state.canWrite,
                modifier = Modifier.weight(1f),
            ) {
                Text("Excel importeren")
            }
        }
        if (!state.canWrite) {
            Text(
                "Importeren is niet beschikbaar voor een alleen-lezen profiel.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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

        ScrollStateButtons(
            state = settingsScrollState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
    if (showLicense) {
        LicenseDialog(onDismiss = { showLicense = false })
    }

    if (showSecuritySetup) {
        AppSecuritySetupDialog(
            biometricName = state.biometricName,
            initialBiometrics = state.biometricsEnabled,
            initialTimeout = state.lockAfterSeconds,
            onDismiss = { showSecuritySetup = false },
            onSave = { pin, biometrics, timeout ->
                showSecuritySetup = false
                controller.configureAppLock(pin, biometrics, timeout)
            },
        )
    }

    if (showSecurityDisable) {
        AppSecurityDisableDialog(
            onDismiss = { showSecurityDisable = false },
            onDisable = { pin ->
                showSecurityDisable = false
                controller.disableAppLock(pin)
            },
        )
    }

    if (addingCategory) {
        CategoryEditorDialog(
            title = "Categorie toevoegen",
            initialCategory = null,
            onDismiss = { addingCategory = false },
            onSave = { label, points ->
                addingCategory = false
                controller.saveCategory(null, label, points)
            },
        )
    }

    editingCategory?.let { category ->
        CategoryEditorDialog(
            title = "Categorie wijzigen",
            initialCategory = category,
            onDismiss = { editingCategory = null },
            onSave = { label, points ->
                editingCategory = null
                controller.saveCategory(category.id, label, points)
            },
        )
    }

    deletingCategory?.let { category ->
        AlertDialog(
            onDismissRequest = { deletingCategory = null },
            title = { Text("Categorie verwijderen?") },
            text = {
                Text(
                    "De categorie '${category.label}' verdwijnt uit de keuzelijst. " +
                        "Bestaande activiteiten behouden hun eerder opgeslagen categorie en punten."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deletingCategory = null
                        controller.deleteCategory(category.id)
                    }
                ) {
                    Text("Verwijderen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCategory = null }) { Text("Annuleer") }
            },
        )
    }

    if (addingPreset) {
        ActivityPresetEditorDialog(
            title = "Standaardactiviteit toevoegen",
            initialPreset = null,
            categories = state.categories,
            onDismiss = { addingPreset = false },
            onSave = { label, categoryId ->
                addingPreset = false
                controller.saveActivityPreset(null, label, categoryId)
            },
        )
    }

    editingPreset?.let { preset ->
        ActivityPresetEditorDialog(
            title = "Standaardactiviteit wijzigen",
            initialPreset = preset,
            categories = state.categories,
            onDismiss = { editingPreset = null },
            onSave = { label, categoryId ->
                editingPreset = null
                controller.saveActivityPreset(preset.id, label, categoryId)
            },
        )
    }

    deletingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletingPreset = null },
            title = { Text("Standaardactiviteit verwijderen?") },
            text = {
                Text(
                    "De standaardactiviteit '${preset.label}' wordt uit de keuzelijst verwijderd. " +
                        "Bestaande activiteiten blijven ongewijzigd."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deletingPreset = null
                        controller.deleteActivityPreset(preset.id)
                    }
                ) {
                    Text("Verwijderen")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPreset = null }) { Text("Annuleer") }
            },
        )
    }

    if (showExcelImport) {
        val parsedYear = importYear.toIntOrNull()
        AlertDialog(
            onDismissRequest = { showExcelImport = false },
            title = { Text("Excel importeren") },
            text = {
                Column {
                    Text(
                        "Oudere dagschema's hebben geen jaartal in de bladnaam. " +
                            "Kies het jaar dat daarvoor gebruikt moet worden."
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importYear,
                        onValueChange = { importYear = it.filter(Char::isDigit).take(4) },
                        label = { Text("Jaar") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val year = requireNotNull(parsedYear)
                        showExcelImport = false
                        controller.importExcel(year)
                    },
                    enabled = parsedYear != null && parsedYear in 1900..2200,
                ) {
                    Text("Bestand kiezen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExcelImport = false }) { Text("Annuleer") }
            },
        )
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
private fun ActivityPresetDropdown(
    presets: List<ActivityPreset>,
    categories: List<ActivityCategory>,
    onSelected: (ActivityPreset, ActivityCategory) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Standaardactiviteit kiezen ▼")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            presets.forEach { preset ->
                val category = categories.firstOrNull { it.id == preset.categoryId }
                if (category != null) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(preset.label)
                                Text(
                                    "${category.label} · ${signed(category.pointsPer30Minutes)} per 30 min",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelected(preset, category)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityPresetEditorDialog(
    title: String,
    initialPreset: ActivityPreset?,
    categories: List<ActivityCategory>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var label by remember(initialPreset?.id) {
        mutableStateOf(initialPreset?.label.orEmpty())
    }
    var categoryId by remember(initialPreset?.id, categories) {
        mutableStateOf(
            initialPreset?.categoryId
                ?.takeIf { id -> categories.any { it.id == id } }
                ?: categories.firstOrNull()?.id
                .orEmpty()
        )
    }
    var expanded by remember { mutableStateOf(false) }
    val selectedCategory = categories.firstOrNull { it.id == categoryId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Naam activiteit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Categorie", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            selectedCategory?.let {
                                "${it.label} (${signed(it.pointsPer30Minutes)} per 30 min) ▼"
                            } ?: "Categorie kiezen ▼"
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${category.label} (${signed(category.pointsPer30Minutes)} per 30 min)"
                                    )
                                },
                                onClick = {
                                    categoryId = category.id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(label, categoryId) },
                enabled = label.isNotBlank() && selectedCategory != null,
            ) {
                Text("Opslaan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleer") }
        },
    )
}

@Composable
private fun CategoryEditorDialog(
    title: String,
    initialCategory: ActivityCategory?,
    onDismiss: () -> Unit,
    onSave: (String, Double) -> Unit,
) {
    var label by remember(initialCategory?.id) {
        mutableStateOf(initialCategory?.label.orEmpty())
    }
    var pointsText by remember(initialCategory?.id) {
        mutableStateOf(
            initialCategory?.pointsPer30Minutes
                ?.let(::formatPoints)
                .orEmpty()
        )
    }
    val points = pointsText.replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Naam categorie") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pointsText,
                    onValueChange = { pointsText = it },
                    label = { Text("Punten per 30 minuten") },
                    supportingText = { Text("Negatieve en decimale waarden zijn toegestaan.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(label, requireNotNull(points)) },
                enabled = label.isNotBlank() && points != null && points.isFinite(),
            ) {
                Text("Opslaan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleer") }
        },
    )
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
private fun LazyListScrollButtons(
    state: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    if (vertical) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ScrollUpButton(
                enabled = itemCount > 0 && state.canScrollBackward,
                onClick = {
                    scope.launch { state.animateScrollToItem(0) }
                },
            )
            ScrollDownButton(
                enabled = itemCount > 0 && state.canScrollForward,
                onClick = {
                    scope.launch {
                        state.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
                    }
                },
            )
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ScrollUpButton(
                enabled = itemCount > 0 && state.canScrollBackward,
                onClick = {
                    scope.launch { state.animateScrollToItem(0) }
                },
            )
            ScrollDownButton(
                enabled = itemCount > 0 && state.canScrollForward,
                onClick = {
                    scope.launch {
                        state.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
                    }
                },
            )
        }
    }
}

@Composable
private fun ScrollStateButtons(
    state: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScrollUpButton(
            enabled = state.value > 0,
            onClick = {
                scope.launch { state.animateScrollTo(0) }
            },
        )
        ScrollDownButton(
            enabled = state.value < state.maxValue,
            onClick = {
                scope.launch { state.animateScrollTo(state.maxValue) }
            },
        )
    }
}

@Composable
private fun ScrollUpButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("↑")
    }
}

@Composable
private fun ScrollDownButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("↓")
    }
}

@Composable
private fun PairingInvitationDialog(
    invitation: net.dikkenberg.activiteitenweger.model.PairingInvitation,
    onClose: () -> Unit,
    onRevoke: () -> Unit,
) {
    val qrPainter = rememberQrCodePainter(invitation.qrPayload)
    var copied by remember(invitation.code) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Apparaat koppelen") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Toegang: ${invitation.access.name} · geldig gedurende ongeveer ${invitation.expiresInSeconds / 60} minuten",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Image(
                    painter = qrPainter,
                    contentDescription = "QR-koppelcode",
                    modifier = Modifier.size(240.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("Handmatige koppelcode", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(invitation.code, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            copyTextToClipboard("Activiteitenweger koppelcode", invitation.code)
                            copied = true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (copied) "Gekopieerd" else "Kopiëren")
                    }
                    OutlinedButton(
                        onClick = {
                            shareText(
                                text = invitation.code,
                                chooserTitle = "Koppelcode delen",
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Delen")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "De code geeft toegang tot de versleutelde sleutel van dit profiel. Deel hem alleen met het apparaat dat je wilt koppelen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Sluiten") }
        },
        dismissButton = {
            TextButton(onClick = onRevoke) { Text("Koppelcode intrekken") }
        },
    )
}

@Composable
private fun RecoveryCredentialDialog(
    recovery: net.dikkenberg.activiteitenweger.model.RecoveryCredential,
    onClose: () -> Unit,
    onSaveToPasswordManager: () -> Unit,
) {
    var copied by remember(recovery.code) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Herstelbackup bewaren") },
        text = {
            Column {
                Text(
                    "Bewaar deze herstelcode buiten dit apparaat. De server bewaart de geheime code niet en deze code wordt maar één keer getoond.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(
                        recovery.code,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            copyTextToClipboard("Activiteitenweger herstelcode", recovery.code)
                            copied = true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (copied) "Gekopieerd" else "Kopiëren")
                    }
                    OutlinedButton(
                        onClick = {
                            shareText(
                                text = recovery.code,
                                chooserTitle = "Herstelcode bewaren of delen",
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Delen")
                    }
                }
                if (passwordManagerSaveAvailable()) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onSaveToPasswordManager,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Opslaan in wachtwoordmanager")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Wie deze code bezit kan het eigenaarschap van dit profiel herstellen. Bewaar hem daarom alleen op een plek die je vertrouwt, bijvoorbeeld een wachtwoordmanager.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onClose) { Text("Ik heb de code veilig bewaard") }
        },
    )
}

@Composable
private fun RecoveryCodeEntryDialog(
    onDismiss: () -> Unit,
    onRecover: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profiel herstellen") },
        text = {
            Column {
                Text("Plak de herstelcode die je eerder buiten dit apparaat hebt bewaard.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    label = { Text("Herstelcode") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Na succesvol herstel wordt deze herstelcode ongeldig. Maak daarna vanuit het herstelde profiel een nieuwe herstelbackup.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRecover(code) },
                enabled = code.startsWith("AWREC1:", ignoreCase = true),
            ) {
                Text("Herstellen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleer") }
        },
    )
}

@Composable
private fun JoinPairingDialog(
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    if (scanning) {
        AlertDialog(
            onDismissRequest = { scanning = false },
            title = { Text("QR-code scannen") },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CameraPermissionGate {
                        ScannerView(
                            modifier = Modifier.fillMaxSize(),
                            codeTypes = listOf(BarcodeFormat.FORMAT_QR_CODE),
                            scannerUiOptions = null,
                        ) { result ->
                            when (result) {
                                is BarcodeResult.OnSuccess -> {
                                    code = result.barcode.data
                                    scanning = false
                                    scanError = null
                                }
                                is BarcodeResult.OnFailed -> {
                                    scanError = result.exception.message ?: "Scannen mislukt"
                                }
                                BarcodeResult.OnCanceled -> scanning = false
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { scanning = false }) { Text("Annuleer") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bestaand profiel koppelen") },
        text = {
            Column {
                Text("Scan de QR-code of vul de handmatige koppelcode in.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Koppelcode") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scanning = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("QR-code scannen")
                }
                scanError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onJoin(code) },
                enabled = code.isNotBlank(),
            ) {
                Text("Koppelen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuleer") }
        },
    )
}

@Composable
private fun ConflictResolverDialog(
    conflict: net.dikkenberg.activiteitenweger.model.ActivityConflictSnapshot,
    onUseMine: () -> Unit,
    onUseServer: () -> Unit,
) {
    fun describe(
        payload: net.dikkenberg.activiteitenweger.model.ActivityRecordPayload?,
        deleted: Boolean,
        revision: Long,
    ): String {
        if (deleted) return "Verwijderd · revision $revision"
        if (payload == null) return "Versleutelde profielinstelling · revision $revision"
        val end = payload.endedAt?.let(::formatLocalTime) ?: "lopend"
        return buildString {
            append(payload.description)
            append("\n")
            append(payload.category.label)
            append(" · ")
            append(formatLocalTime(payload.startedAt))
            append(" – ")
            append(end)
            append("\nrevision ")
            append(revision)
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Synchronisatieconflict") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dit item is op twee apparaten gewijzigd. Kies welke versie moet blijven.")
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Jouw versie", style = MaterialTheme.typography.titleSmall)
                        Text(describe(conflict.localPayload, conflict.localDeleted, conflict.localRevision))
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Serverversie", style = MaterialTheme.typography.titleSmall)
                        Text(describe(conflict.remotePayload, conflict.remoteDeleted, conflict.remoteRevision))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onUseMine) { Text("Mijn versie gebruiken") }
        },
        dismissButton = {
            OutlinedButton(onClick = onUseServer) { Text("Serverversie gebruiken") }
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

private fun dutchWeekday(value: String): String =
    when (LocalDate.parse(value).dayOfWeek.name) {
        "MONDAY" -> "Maandag"
        "TUESDAY" -> "Dinsdag"
        "WEDNESDAY" -> "Woensdag"
        "THURSDAY" -> "Donderdag"
        "FRIDAY" -> "Vrijdag"
        "SATURDAY" -> "Zaterdag"
        "SUNDAY" -> "Zondag"
        else -> ""
    }

private fun formatIsoDateForDisplay(value: String): String {
    val date = LocalDate.parse(value)
    return "${date.day.toString().padStart(2, '0')}-" +
        "${(date.month.ordinal + 1).toString().padStart(2, '0')}-${date.year}"
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

private fun formatLocalDateTime(value: String): String {
    val dateTime = kotlin.time.Instant.parse(value)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    return dateTime.dayOfMonth.toString().padStart(2, '0') + "-" +
        dateTime.monthNumber.toString().padStart(2, '0') + "-" + dateTime.year + " " +
        dateTime.hour.toString().padStart(2, '0') + ":" +
        dateTime.minute.toString().padStart(2, '0')
}

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

private fun formatLockTimeout(seconds: Long): String =
    when (seconds) {
        0L -> "direct"
        60L -> "na 1 minuut"
        300L -> "na 5 minuten"
        900L -> "na 15 minuten"
        else -> "na " + seconds + " seconden"
    }

private fun formatPoints(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (abs(rounded - rounded.toInt()) < 0.0001) rounded.toInt().toString() else rounded.toString()
}

private fun signed(value: Double): String =
    if (value > 0) "+" + formatPoints(value) else formatPoints(value)
