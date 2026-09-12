package net.dikkenberg.activiteitenweger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.dikkenberg.activiteitenweger.AppController
import net.dikkenberg.activiteitenweger.AppUiState
import net.dikkenberg.activiteitenweger.model.ActivityCategory
import net.dikkenberg.activiteitenweger.model.ActivityItem
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
            Destination.HISTORY -> HistoryScreen(state)
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
    }
}

@Composable
private fun TodayScreen(state: AppUiState, controller: AppController) {
    var showStart by remember { mutableStateOf(false) }
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
                ActivityCard(item, now, canDelete = state.canWrite) {
                    controller.deleteActivity(item)
                }
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
}

@Composable
private fun ActivityCard(item: ActivityItem, now: kotlin.time.Instant, canDelete: Boolean, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.payload.description, style = MaterialTheme.typography.titleMedium)
                Text(timeRange(item))
                Text("${item.payload.category.label} · ${formatDuration(item.payload.durationSeconds(now))}")
                Text("${formatPoints(item.payload.points(now))} punten", style = MaterialTheme.typography.bodySmall)
            }
            if (item.payload.endedAt != null && canDelete) {
                TextButton(onClick = onDelete) { Text("Verwijder") }
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

@Composable
private fun HistoryScreen(state: AppUiState) {
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
            items(items.size) { index -> ActivityCard(items[index], Clock.System.now(), false) {} }
        }
    }
}

@Composable
private fun ProfilesScreen(state: AppUiState, controller: AppController) {
    var newProfile by remember { mutableStateOf(false) }
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
                    Text(session.access.name)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { newProfile = true }) { Text("Nieuw eigen profiel") }
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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Instellingen", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("Server: ${state.health}")
        Text("https://app.dikkenberg.net")
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
