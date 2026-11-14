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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import com.example.dueo.R
import com.example.dueo.data.*
import com.example.dueo.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueoApp() {
    val activity = LocalContext.current as? ComponentActivity
    val context = LocalContext.current.applicationContext as Application
    val viewModel = remember(activity) {
        if (activity != null) {
            ViewModelProvider(activity, DueoViewModel.factory(context))[DueoViewModel::class.java]
        } else {
            DueoViewModel(context)
        }
    }
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

    // Launcher for exporting damaged ledger file on recovery screen
    val exportCorruptedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val corrupted = uiState as? LedgerUiState.Corrupted
            if (corrupted != null) {
                scope.launch {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            val bytes = if (!corrupted.rawContent.isNullOrBlank()) {
                                corrupted.rawContent.toByteArray(Charsets.UTF_8)
                            } else if (corrupted.backupPath != null) {
                                File(corrupted.backupPath).readBytes()
                            } else {
                                "{}".toByteArray(Charsets.UTF_8)
                            }
                            stream.write(bytes)
                            stream.flush()
                        }
                        snackbar.showSnackbar("Damaged ledger saved to external storage")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Failed to export damaged ledger: ${e.message}")
                    }
                }
            }
        }
    }

    // Common Restore Backup Launcher
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.inspectBackupUri(uri) { res ->
                if (res.isFailure) {
                    val err = res.exceptionOrNull()?.message ?: "Unable to read backup"
                    scope.launch { snackbar.showSnackbar("Invalid backup: $err") }
                }
            }
        }
    }

    // Common Export Backup Launcher (.dueobak ZIP container)
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackupZip(uri) { res ->
                if (res.isSuccess) {
                    val manifest = res.getOrThrow()
                    scope.launch {
                        snackbar.showSnackbar(
                            "Backup exported successfully (${manifest.peopleCount} people, ${manifest.entriesCount} transactions, ${manifest.photosCount} photos)"
                        )
                    }
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Export failed"
                    scope.launch { snackbar.showSnackbar("Failed to export backup: $err") }
                }
            }
        }
    }

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
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { viewModel.retryLoad() }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Retry load")
                                        }
                                        OutlinedButton(
                                            onClick = { exportCorruptedLauncher.launch("dueo-damaged-${System.currentTimeMillis()}.json") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Export damaged file")
                                        }
                                        OutlinedButton(
                                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Restore from backup")
                                        }
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
                                        viewModel.shareBalance(
                                            person = selectedProfile,
                                            balance = entries.filter { it.personId == selectedProfile.id }.balance(),
                                            onSuccess = { uri ->
                                                StatementSharer.shareImage(context, uri, "Share balance", "Dueo balance statement with ${selectedProfile.name}")
                                            },
                                            onError = { e ->
                                                scope.launch { snackbar.showSnackbar("Unable to generate balance image: ${e.message}") }
                                            }
                                        )
                                    },
                                    onShareStatementPdf = {
                                        viewModel.shareStatementPdf(
                                            person = selectedProfile,
                                            entries = entries.filter { it.personId == selectedProfile.id },
                                            onSuccess = { uri ->
                                                StatementSharer.sharePdf(context, uri, "Share statement (PDF)", "Dueo transaction statement for ${selectedProfile.name}")
                                            },
                                            onError = { e ->
                                                scope.launch { snackbar.showSnackbar("Unable to generate PDF statement: ${e.message}") }
                                            }
                                        )
                                    },
                                    onShareStatementImage = {
                                        viewModel.shareStatementImages(
                                            person = selectedProfile,
                                            entries = entries.filter { it.personId == selectedProfile.id },
                                            onSuccess = { uris ->
                                                StatementSharer.shareImages(context, uris, "Share statement (Images)", "Dueo transaction statement for ${selectedProfile.name}")
                                            },
                                            onError = { e ->
                                                scope.launch { snackbar.showSnackbar("Unable to generate statement images: ${e.message}") }
                                            }
                                        )
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
                                    onExportBackup = { exportBackupLauncher.launch("dueo-backup-${System.currentTimeMillis()}.dueobak") },
                                    onRestoreBackup = { importLauncher.launch(arrayOf("*/*")) }
                                )
                            }
                        }
                    }
                }

                // Toast notification when transaction saved
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

                // Loading overlay during background statement generation
                if (viewModel.isGeneratingShare) {
                    Dialog(onDismissRequest = {}) {
                        Surface(
                            shape = DueoDesign.CardShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 12.dp
                        ) {
                            Row(
                                Modifier.padding(24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                Text("Preparing statement…", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }

        // Restore Confirmation Dialog with Preview
        viewModel.pendingRestorePreview?.let { preview ->
            AlertDialog(
                onDismissRequest = { if (!viewModel.isRestoringBackup) viewModel.dismissRestorePreview() },
                title = { Text("Restore backup?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Backup details:", style = MaterialTheme.typography.titleSmall)
                        Text("• Format: ${if (preview.isZipArchive) "Portable archive (.dueobak)" else "Legacy JSON file"}")
                        Text("• People: ${preview.peopleCount}")
                        Text("• Transactions: ${preview.entriesCount}")
                        if (preview.earliestDate != null && preview.latestDate != null) {
                            val start = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(preview.earliestDate))
                            val end = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(preview.latestDate))
                            Text("• Activity dates: $start to $end")
                        }
                        Text("• Receivables: ${money(preview.totalReceive)}")
                        Text("• Payables: ${money(preview.totalPay)}")
                        if (preview.isZipArchive) {
                            Text("• Profile photos: ${preview.photosCount} photos included")
                        } else {
                            Text("• Profile photos: None (legacy JSON)", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = DueoDesign.SmallShape, color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)) {
                            Text(
                                "Warning: Restoring this backup will replace current records. A recoverable safety snapshot of your existing data will be created automatically before replacement.",
                                Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.confirmRestore(preview) { res ->
                                if (res.isSuccess) {
                                    val restored = res.getOrThrow()
                                    scope.launch {
                                        snackbar.showSnackbar("Restored ${restored.entries.size} transactions and ${restored.people.size} people successfully")
                                    }
                                } else {
                                    val err = res.exceptionOrNull()?.message ?: "Restore failed"
                                    scope.launch { snackbar.showSnackbar("Restore failed: $err") }
                                }
                            }
                        },
                        enabled = !viewModel.isRestoringBackup,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (viewModel.isRestoringBackup) "Restoring…" else "Replace existing records")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissRestorePreview() },
                        enabled = !viewModel.isRestoringBackup
                    ) { Text("Cancel") }
                }
            )
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
                        ) { personId, amount, kind, note, clientOpId ->
                            viewModel.addTransaction(
                                personId = personId,
                                amount = amount,
                                kind = kind,
                                note = note,
                                clientOperationId = clientOpId
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
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
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
                        "Your financial records stay private on your device. Export a portable backup container (.dueobak) including all profile photos and transaction ledgers to transfer records safely.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onExportBackup,
                            modifier = Modifier.weight(1f)
                        ) { Text("Export backup") }
                        OutlinedButton(
                            onClick = onRestoreBackup,
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
    val people = remember(entries, registry, query) {
        val queryTrimmed = query.trim()
        val summaries = entries.people(registry)
        summaries
            .filter { queryTrimmed.isEmpty() || it.name.contains(queryTrimmed, ignoreCase = true) || it.phone.contains(queryTrimmed) }
            .sortedBy { it.name.lowercase() }
    }
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("People", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
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
    onShareStatementPdf: () -> Unit,
    onShareStatementImage: () -> Unit
) {
    var showStatementFormatDialog by remember { mutableStateOf(false) }

    if (showStatementFormatDialog) {
        AlertDialog(
            onDismissRequest = { showStatementFormatDialog = false },
            title = { Text("Share Statement") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Select statement format:")
                    OutlinedButton(
                        onClick = {
                            showStatementFormatDialog = false
                            onShareStatementPdf()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("PDF Document (Complete multi-page record)")
                    }
                    OutlinedButton(
                        onClick = {
                            showStatementFormatDialog = false
                            onShareStatementImage()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Image Card (Graphic receipt)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showStatementFormatDialog = false }) { Text("Cancel") }
            }
        )
    }

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
                OutlinedButton(onClick = { showStatementFormatDialog = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
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
