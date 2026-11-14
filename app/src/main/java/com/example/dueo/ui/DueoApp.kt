package com.example.dueo.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.dueo.R
import com.example.dueo.data.*
import com.example.dueo.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueoApp() {
    val activity = LocalContext.current as? ComponentActivity
    val context = LocalContext.current.applicationContext
    val preferences = remember { ThemePreferences(context) }
    val themeFlow = remember { preferences.mode.catch { emit(ThemeMode.SYSTEM) } }
    val mode by themeFlow.collectAsState(initial = ThemeMode.SYSTEM)
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val store = remember { LedgerStore(context) }
    val scope = rememberCoroutineScope()
    var ledger by remember { mutableStateOf(LedgerState()) }
    val entries = ledger.entries
    val people = ledger.people
    var showAddPerson by rememberSaveable { mutableStateOf(false) }
    var personSaving by remember { mutableStateOf(false) }
    var personError by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(2200); saved = false } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showEntry by rememberSaveable { mutableStateOf(false) }
    var showTypes by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    var initialPerson by rememberSaveable { mutableStateOf("") }
    var initialKind by rememberSaveable { mutableStateOf(EntryKind.LENT) }
    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark || (tab == 0 && person == null) },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val transactionSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val backdropScale by animateFloatAsState(if (showTypes || showEntry) .965f else 1f, tween(420, easing = DueoMotion.Ease), label = "sheet depth")
    val add: (EntryKind?) -> Unit = { kind ->
        initialPerson = person.orEmpty()
        if (kind == null) showTypes = true else { initialKind = kind; showEntry = true }
    }
    val setTheme: (ThemeMode) -> Unit = { selected ->
        scope.launch {
            try { preferences.set(selected); showTheme = false }
            catch (_: Exception) { snackbar.showSnackbar("Couldn't save theme. Please try again.") }
        }
    }
    LaunchedEffect(Unit) {
        try { ledger = withContext(Dispatchers.IO) { store.readState() }; loaded = true }
        catch (_: Exception) { loadError = true }
    }
    BackHandler(person != null) { person = null }
    DueoTheme(dark) {
        Scaffold(
            modifier = Modifier.graphicsLayer { scaleX = backdropScale; scaleY = backdropScale },
            containerColor = if (tab == 0 && person == null) androidx.compose.ui.graphics.Color(0xFF111318) else MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { Surface(color = MaterialTheme.colorScheme.background) { PillNavigation(tab) { tab = it; person = null } } }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (loadError) Text("Your records couldn't be opened.\nRestart to retry. Your data is preserved.", Modifier.padding(24.dp)) else CircularProgressIndicator()
                } else AnimatedContent(targetState = person to tab, transitionSpec = { pageMotion(targetState.first != null || (initialState.first == null && targetState.second > initialState.second)) }, label = "screen") { (selectedPerson, page) ->
                    val selectedProfile = people.firstOrNull { it.id == selectedPerson }
                    if (selectedProfile != null) PersonDetail(selectedProfile, entries.filter { it.personId == selectedProfile.id }, { person = null }, add)
                    else when (page) {
                        0 -> HomeScreen(entries, dark, { showTheme = true }, add, { tab = 1 }, { person = it }, people, { showAddPerson = true })
                        1 -> PeopleScreen(entries, people, { person = it }, { showAddPerson = true })
                        2 -> ActivityScreen(entries, { add(null) })
                        else -> SettingsScreen(mode, setTheme)
                    }
                }
                AnimatedVisibility(saved, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                    Surface(shape = DueoDesign.CardShape, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 8.dp) {
                        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SuccessTick()
                            Text("Transaction saved", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        if (showAddPerson) PersonEditor(saving = personSaving, error = personError, onDismiss = { if (!personSaving) showAddPerson = false }) { name, phone, photo ->
            if (!personSaving) {
                personSaving = true
                personError = null
                scope.launch {
                    try {
                        val updated = withContext(Dispatchers.IO) { store.addPerson(ledger, name, phone, photo) }
                        ledger = updated
                        if (showEntry) initialPerson = updated.people.last().id
                        showAddPerson = false
                    } catch (_: Exception) { personError = "Couldn't save person. Please try again." }
                    finally { personSaving = false }
                }
            }
        }
        if (showTheme) AlertDialog(onDismissRequest = { showTheme = false }, title = { Text("Appearance") }, text = {
            ThemeChoices(mode, setTheme)
        }, confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Done") } })
        if (showTypes || showEntry) ModalBottomSheet(
            onDismissRequest = { if (!saving) { showTypes = false; showEntry = false } },
            sheetState = transactionSheet, containerColor = MaterialTheme.colorScheme.surface
        ) {
            AnimatedContent(showTypes, transitionSpec = { sheetMotion(!targetState) }, label = "transaction sheet stage") { choosingType ->
                if (choosingType) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add a transaction", style = MaterialTheme.typography.headlineMedium)
                Text("Choose what happened", color = MaterialTheme.colorScheme.onSurfaceVariant)
                EntryKind.entries.forEach { kind ->
                    OutlinedButton(onClick = { initialKind = kind; showTypes = false; showEntry = true }, modifier = Modifier.enterMotion(kind.ordinal).fillMaxWidth().heightIn(min = 52.dp)) { Text(kind.label) }
                }
            }
                } else {
            EntryForm(entries, initialKind, initialPerson, saving, people, { showAddPerson = true }) { personId, amount, kind, note ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        val selected = people.first { it.id == personId }
                        val updated = ledger.copy(entries = entries + Entry(person = selected.name, personId = selected.id, amount = amount, kind = kind, note = note))
                        try {
                            withContext(Dispatchers.IO) { store.writeState(updated) }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            saving = false
                            snackbar.showSnackbar("Couldn't save. Please try again.")
                            return@launch
                        }
                        ledger = updated
                        try { transactionSheet.hide() }
                        finally { showEntry = false; saving = false; saved = true }
                    }
                }
            }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(mode: ThemeMode, setTheme: (ThemeMode) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item {
            Surface(shape = DueoDesign.CardShape) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleLarge)
                    ThemeChoices(mode, setTheme)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.dueo_app_icon), "Dueo logo", Modifier.size(64.dp))
                Text("dueo", style = MaterialTheme.typography.headlineMedium)
                Text("Shared dues, made simple.", style = MaterialTheme.typography.bodyMedium)
                Text("Your records stay on this device. No account needed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ThemeChoices(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column {
        ThemeMode.entries.forEach { option ->
            Surface(onClick = { onSelect(option) }, color = androidx.compose.ui.graphics.Color.Transparent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(option.label)
                }
            }
        }
    }
}

@Composable
private fun PeopleScreen(entries: List<Entry>, registry: List<Person>, onPerson: (String) -> Unit, onAdd: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val people = entries.people(registry).filter { it.name.contains(query, ignoreCase = true) || it.phone.contains(query) }
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your people", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onAdd) { LineIcon(Glyph.ADD, "Add person") }
            }
        }
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search people") }, leadingIcon = { LineIcon(Glyph.SEARCH) }, singleLine = true, shape = DueoDesign.SmallShape) }
        if (people.isEmpty()) item { Text(if (query.isBlank()) "Add your first person, then record money lent or borrowed." else "No matching people.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        items(people, key = { it.id }) { summary -> PersonRow(summary, Modifier.animateItem()) { onPerson(summary.id) } }
    }
}

@Composable
private fun ActivityScreen(entries: List<Entry>, onAdd: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onAdd) { LineIcon(Glyph.ADD, "Add transaction") }
            }
        }
        if (entries.isEmpty()) item { Text("Your transactions will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(entries.sortedByDescending { it.date }, key = { it.id }) { Box(Modifier.animateItem().enterMotion()) { EntryRow(it) } }
    }
}

@Composable
private fun PersonDetail(person: Person, entries: List<Entry>, onBack: () -> Unit, onAdd: (EntryKind?) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { LineIcon(Glyph.BACK, "Back") }
                Avatar(person.name, photoPath = person.photoPath)
                Text(person.name, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.titleLarge)
            }
        }
        item { if (person.phone.isNotBlank()) Text(person.phone, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { BalanceOverview(entries.balance()) }
        item { Button(onClick = { onAdd(null) }, modifier = Modifier.fillMaxWidth()) { Text("Record transaction or repayment") } }
        item { Text("Transaction history", style = MaterialTheme.typography.titleLarge) }
        items(entries.sortedByDescending { it.date }, key = { it.id }) { Box(Modifier.animateItem().enterMotion()) { EntryRow(it) } }
    }
}
