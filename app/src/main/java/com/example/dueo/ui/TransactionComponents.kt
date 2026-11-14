package com.example.dueo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.dueo.data.*
import com.example.dueo.ui.theme.DueoDesign
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
internal fun EntryRow(entry: Entry, onEdit: ((Entry) -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    val isClickable = onEdit != null
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = if (isClickable) {
            Modifier.clickable(interactionSource = source, indication = null, role = Role.Button) { onEdit(entry) }
        } else Modifier
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(entry.person.take(1).uppercase(), Modifier.padding(14.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.person, fontWeight = FontWeight.SemiBold)
                    Text(money(entry.amount), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(entry.kind.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(entry.date)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.dueDate != null) {
                    Text("Due by: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(entry.dueDate))}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (entry.note.isNotBlank()) {
                    Text(entry.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
internal fun EditTransactionDialog(
    entry: Entry,
    saving: Boolean,
    onDismiss: () -> Unit,
    onDelete: (Entry) -> Unit,
    onSave: (Entry) -> Unit
) {
    val context = LocalContext.current
    var amountText by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", entry.amount / 100.0)) }
    var noteText by rememberSaveable { mutableStateOf(entry.note) }
    var selectedDate by rememberSaveable { mutableLongStateOf(entry.date) }
    var selectedDueDate by rememberSaveable { mutableStateOf<Long?>(entry.dueDate) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    val showDatePicker = { initialDate: Long, onPicked: (Long) -> Unit ->
        val cal = Calendar.getInstance().apply { timeInMillis = initialDate }
        android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 12)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                onPicked(picked.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete transaction?") },
            text = { Text("This will permanently remove this ${entry.kind.label} of ${money(entry.amount)} with ${entry.person}. Balance records will be recalculated.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete(entry)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Edit transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${entry.kind.label} with ${entry.person}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; localError = null },
                    label = { Text("Amount (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = !saving,
                    shape = DueoDesign.SmallShape,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it.take(240); localError = null },
                    label = { Text("Note (optional)") },
                    singleLine = false,
                    maxLines = 3,
                    enabled = !saving,
                    shape = DueoDesign.SmallShape,
                    modifier = Modifier.fillMaxWidth()
                )

                // Date Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Transaction date", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(dateFormatter.format(Date(selectedDate)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(
                        onClick = { showDatePicker(selectedDate) { selectedDate = it; localError = null } },
                        enabled = !saving
                    ) { Text("Change") }
                }

                // Due Date Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Due date", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (selectedDueDate != null) dateFormatter.format(Date(selectedDueDate!!)) else "None set",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selectedDueDate != null) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                    Row {
                        if (selectedDueDate != null) {
                            TextButton(
                                onClick = { selectedDueDate = null; localError = null },
                                enabled = !saving
                            ) { Text("Clear") }
                        }
                        TextButton(
                            onClick = {
                                val init = selectedDueDate ?: (selectedDate + 7 * 86400000L)
                                showDatePicker(init) { selectedDueDate = it; localError = null }
                            },
                            enabled = !saving
                        ) { Text(if (selectedDueDate != null) "Change" else "Set due date") }
                    }
                }

                if (localError != null) {
                    Text(localError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = parseAmount(amountText.trim())
                    if (parsed == null || parsed <= 0) {
                        localError = "Please enter a valid amount greater than zero."
                        return@Button
                    }
                    val updated = entry.copy(
                        amount = parsed,
                        note = noteText.trim(),
                        date = selectedDate,
                        dueDate = selectedDueDate
                    )
                    onSave(updated)
                },
                enabled = !saving
            ) { Text(if (saving) "Saving…" else "Save changes") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = !saving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
            }
        }
    )
}
