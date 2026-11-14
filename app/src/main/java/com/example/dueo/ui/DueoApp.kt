package com.example.dueo.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.dueo.R
import com.example.dueo.data.*
import com.example.dueo.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueoApp() {
    val activity = LocalContext.current as? ComponentActivity
    val context = LocalContext.current.applicationContext as Application
    val viewModel = remember { DueoViewModel(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val mode by viewModel.themeMode.collectAsState()
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())

    val uiState by viewModel.uiState.collectAsState()

    var showAddPerson by rememberSaveable { mutableStateOf(false) }
    var savedToast by remember { mutableStateOf(false) }
    LaunchedEffect(savedToast) {
        if (savedToast) {
            delay(2200)
            savedToast = false
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showEntry by rememberSaveable { mutableStateOf(false) }
    var showTypes by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var selectedPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialPerson by rememberSaveable { mutableStateOf("") }
    var initialKind by rememberSaveable { mutableStateOf(EntryKind.LENT) }

    var editingEntry by remember { mutableStateOf<Entry?>(null) }

    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        )
    }

    val transactionSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val backdropScale by animateFloatAsState(
        if (showTypes || showEntry) .965f else 1f,
        tween(420, easing = DueoMotion.Ease),
        label = "sheet depth"
    )

    val add: (EntryKind?) -> Unit = { kind ->
        initialPerson = selectedPersonId.orEmpty()
        if (kind == null) showTypes = true else { initialKind = kind; showEntry = true }
    }

    BackHandler(selectedPersonId != null) { selectedPersonId = null }

    DueoTheme(dark) {
        Scaffold(
            modifier = Modifier.graphicsLayer { scaleX = backdropScale; scaleY = backdropScale },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PillNavigation(tab) {
                        tab = it
                        selectedPersonId = null
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val state = uiState) {
                    is LedgerUiState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is LedgerUiState.Corrupted -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Card(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                shape = DueoDesign.CardShape,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("Ledger recovery required", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                                    Text(
                                        "An issue was detected while opening your ledger: ${state.message}.\n\n" +
                                            "Your original data was safely preserved at:\n${state.backupPath ?: "local backup"}.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { viewModel.retryLoad() }) { Text("Retry load") }
                                    }
                                }
                            }
                        }
                    }
                    is LedgerUiState.Unsupported -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Unsupported ledger version ${state.version}.\nPlease update Dueo to open this file.",
                                Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    is LedgerUiState.Error -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(state.message, color = MaterialTheme.colorScheme.error)
                                Button(onClick = { viewModel.retryLoad() }) { Text("Retry") }
                            }
                        }
                    }
                    is LedgerUiState.Ready -> {
                        val ledger = state.ledger
                        val people = ledger.people
                        val entries = ledger.entries

                        AnimatedContent(
                            targetState = selectedPersonId to tab,
                            transitionSpec = { pageMotion(targetState.first != null || (initialState.first == null && targetState.second > initialState.second)) },
                            label = "screen"
                        ) { (currentPersonId, page) ->
                            val selectedProfile = people.firstOrNull { it.id == currentPersonId }
                            if (selectedProfile != null) {
                                PersonDetail(
                                    person = selectedProfile,
                                    entries = entries.filter { it.personId == selectedProfile.id },
                                    onBack = { selectedPersonId = null },
                                    onAdd = add,
                                    onEditEntry = { editingEntry = it },
                                    onShareBalance = {
                                        try {
                                            val uri = StatementSharer.generateBalanceImage(context, selectedProfile, entries.filter { it.personId == selectedProfile.id }.balance())
                                            StatementSharer.shareImage(context, uri, "Share balance", "Dueo balance statement with ${selectedProfile.name}")
                                        } catch (e: Exception) {
                                            scope.launch { snackbar.showSnackbar("Unable to generate balance image: ${e.message}") }
                                        }
                                    },
                                    onShareStatement = {
                                        try {
                                            val uri = StatementSharer.generateStatementImage(context, selectedProfile, entries.filter { it.personId == selectedProfile.id })
                                            StatementSharer.shareImage(context, uri, "Share statement", "Dueo transaction statement for ${selectedProfile.name}")
                                        } catch (e: Exception) {
                                            scope.launch { snackbar.showSnackbar("Unable to generate statement image: ${e.message}") }
                                        }
                                    }
                                )
                            } else when (page) {
                                0 -> HomeScreen(
                                    entries = entries,
                                    dark = dark,
                                    onTheme = { showTheme = true },
                                    onAdd = add,
                                    onPeople = { tab = 1 },
                                    onPerson = { selectedPersonId = it },
                                    registry = people,
                                    onAddPerson = { showAddPerson = true }
                                )
                                1 -> PeopleScreen(
                                    entries = entries,
                                    registry = people,
                                    onPerson = { selectedPersonId = it },
                                    onAdd = { showAddPerson = true }
                                )
                                2 -> ActivityScreen(
                                    entries = entries,
                                    onAdd = { add(null) },
                                    onEditEntry = { editingEntry = it }
                                )
                                else -> SettingsScreen(
                                    mode = mode,
                                    setTheme = { viewModel.setTheme(it); showTheme = false },
                                    viewModel = viewModel,
                                    snackbar = snackbar
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    savedToast,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                ) {
                    Surface(shape = DueoDesign.CardShape, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 8.dp) {
                        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SuccessTick()
                            Text("Transaction saved", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }

        // Add Person Dialog
        if (showAddPerson) {
            PersonEditor(
                saving = viewModel.isSavingPerson,
                error = viewModel.personError,
                onDismiss = { if (!viewModel.isSavingPerson) showAddPerson = false }
            ) { name, phone, photo ->
                viewModel.addPerson(name, phone, photo) { created ->
                    if (showEntry) initialPerson = created.id
                    showAddPerson = false
                }
            }
        }

        // Theme Dialog
        if (showTheme) {
            AlertDialog(
                onDismissRequest = { showTheme = false },
                title = { Text("Appearance") },
                text = {
                    ThemeChoices(mode) {
                        viewModel.setTheme(it)
                        showTheme = false
                    }
                },
                confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Done") } }
            )
        }

        // Edit Transaction Dialog
        editingEntry?.let { entry ->
            EditTransactionDialog(
                entry = entry,
                saving = viewModel.isSavingTransaction,
                onDismiss = { editingEntry = null },
                onDelete = {
                    viewModel.deleteTransaction(entry.id) { res ->
                        if (res.isSuccess) {
                            editingEntry = null
                            scope.launch { snackbar.showSnackbar("Transaction deleted") }
                        } else {
                            scope.launch { snackbar.showSnackbar(res.exceptionOrNull()?.message ?: "Delete failed") }
                        }
                    }
                },
                onSave = { updated ->
                    viewModel.updateTransaction(updated) { res ->
                        if (res.isSuccess) {
                            editingEntry = null
                            scope.launch { snackbar.showSnackbar("Transaction updated") }
                        } else {
                            scope.launch { snackbar.showSnackbar(res.exceptionOrNull()?.message ?: "Update failed") }
                        }
                    }
                }
            )
        }

        // Add Transaction Bottom Sheet
        if (showTypes || showEntry) {
            val readyLedger = (uiState as? LedgerUiState.Ready)?.ledger ?: LedgerState()
            ModalBottomSheet(
                onDismissRequest = { if (!viewModel.isSavingTransaction) { showTypes = false; showEntry = false } },
                sheetState = transactionSheet,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                AnimatedContent(showTypes, transitionSpec = { sheetMotion(!targetState) }, label = "transaction sheet stage") { choosingType ->
                    if (choosingType) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Add a transaction", style = MaterialTheme.typography.headlineMedium)
                            Text("Choose what happened", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            EntryKind.entries.forEach { kind ->
                                OutlinedButton(
                                    onClick = { initialKind = kind; showTypes = false; showEntry = true },
                                    modifier = Modifier.enterMotion(kind.ordinal).fillMaxWidth().heightIn(min = 52.dp)
                                ) { Text(kind.label) }
                            }
                        }
                    } else {
                        EntryForm(
                            entries = readyLedger.entries,
                            initialKind = initialKind,
                            initialPerson = initialPerson,
                            saving = viewModel.isSavingTransaction,
                            people = readyLedger.people,
                            onAddPerson = { showAddPerson = true }
                        ) { personId, amount, kind, note ->
                            viewModel.addTransaction(
                                personId = personId,
                                amount = amount,
                                kind = kind,
                                note = note
                            ) { result ->
                                if (result.isSuccess) {
                                    scope.launch {
                                        try { transactionSheet.hide() } finally {
                                            showEntry = false
                                            savedToast = true
                                        }
                                    }
                                } else {
                                    val err = result.exceptionOrNull()?.message ?: "Couldn't save transaction"
                                    scope.launch { snackbar.showSnackbar(err) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    mode: ThemeMode,
    setTheme: (ThemeMode) -> Unit,
    viewModel: DueoViewModel,
    snackbar: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val exportRes = viewModel.exportJson()
                if (exportRes.isSuccess) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(exportRes.getOrThrow().toByteArray(Charsets.UTF_8))
                        }
                        snackbar.showSnackbar("Backup exported successfully")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Failed to write backup: ${e.message}")
                    }
                } else {
                    snackbar.showSnackbar("Failed to prepare backup")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    }
                    if (raw != null) {
                        viewModel.restoreFromJson(raw) { res ->
                            if (res.isSuccess) {
                                scope.launch { snackbar.showSnackbar("Restored ${res.getOrNull()} transactions successfully") }
                            } else {
                                scope.launch { snackbar.showSnackbar("Restore failed: ${res.exceptionOrNull()?.message}") }
                            }
                        }
                    }
                } catch (e: Exception) {
                    snackbar.showSnackbar("Failed to read file: ${e.message}")
                }
            }
        }
    }

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
            Surface(shape = DueoDesign.CardShape) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Backup & Restore", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Your financial records stay private on your device. Export a backup to save a copy or transfer records to another device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { exportLauncher.launch("dueo-backup-${System.currentTimeMillis()}.json") },
                            modifier = Modifier.weight(1f)
                        ) { Text("Export backup") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Restore backup") }
                    }
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
        item {
            OutlinedTextField(
                query, { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search people") },
                leadingIcon = { LineIcon(Glyph.SEARCH) },
                singleLine = true,
                shape = DueoDesign.SmallShape
            )
        }
        if (people.isEmpty()) item {
            Text(
                if (query.isBlank()) "Add your first person, then record money lent or borrowed." else "No matching people.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        items(people, key = { it.id }) { summary ->
            PersonRow(summary, Modifier.animateItem()) { onPerson(summary.id) }
        }
    }
}

@Composable
private fun ActivityScreen(
    entries: List<Entry>,
    onAdd: () -> Unit,
    onEditEntry: (Entry) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Activity", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onAdd) { LineIcon(Glyph.ADD, "Add transaction") }
            }
        }
        if (entries.isEmpty()) item { Text("Your transactions will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(entries.sortedByDescending { it.date }, key = { it.id }) { entry ->
            Box(Modifier.animateItem().enterMotion()) {
                EntryRow(entry, onEdit = onEditEntry)
            }
        }
    }
}

@Composable
private fun PersonDetail(
    person: Person,
    entries: List<Entry>,
    onBack: () -> Unit,
    onAdd: (EntryKind?) -> Unit,
    onEditEntry: (Entry) -> Unit,
    onShareBalance: () -> Unit,
    onShareStatement: () -> Unit
) {
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
        item {
            Button(onClick = { onAdd(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Record transaction or repayment")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onShareBalance, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Share balance")
                }
                OutlinedButton(onClick = onShareStatement, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Share statement")
                }
            }
        }
        item { Text("Transaction history", style = MaterialTheme.typography.titleLarge) }
        items(entries.sortedByDescending { it.date }, key = { it.id }) { entry ->
            Box(Modifier.animateItem().enterMotion()) {
                EntryRow(entry, onEdit = onEditEntry)
            }
        }
    }
}
