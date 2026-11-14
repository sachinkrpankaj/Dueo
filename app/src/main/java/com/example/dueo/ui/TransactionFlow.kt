package com.example.dueo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dueo.data.*
import com.example.dueo.ui.theme.DueoDesign

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun EntryForm(entries: List<Entry>, initialKind: EntryKind, initialPerson: String = "", saving: Boolean, people: List<Person>, onAddPerson: () -> Unit, onSave: (String, Long, EntryKind, String) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf(initialPerson) }
    var query by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableIntStateOf(if (initialPerson.isBlank()) 0 else 1) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(initialPerson) { if (initialPerson.isNotBlank()) { selectedId = initialPerson; step = 1 } }
    val selected = people.firstOrNull { it.id == selectedId }
    val canonical = selected?.name.orEmpty()
    val parsed = parseAmount(amount.trimEnd('.'))
    val balance = entries.filter { it.personId == selectedId }.balance()
    val error = when {
        selected == null -> "Choose a saved person first."
        parsed == null -> "Enter an amount greater than zero."
        initialKind == EntryKind.RECEIVED && parsed > balance.receive -> "You can receive up to ${money(balance.receive)}."
        initialKind == EntryKind.PAID && parsed > balance.pay -> "You can repay up to ${money(balance.pay)}."
        else -> null
    }
    BackHandler(step > 0 && !saving) { step-- }
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) IconButton(onClick = { if (!saving) step-- }, enabled = !saving) { LineIcon(Glyph.BACK, "Previous step") }
            Text(initialKind.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${step + 1} / 3", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { index ->
                val color by animateColorAsState(if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, tween(300), label = "step progress")
                Box(Modifier.weight(1f).height(3.dp).background(color, CircleShape))
            }
        }
        SharedTransitionLayout {
        AnimatedContent(step, transitionSpec = { sheetMotion(targetState > initialState) }, label = "transaction step") { page ->
            val transitionScope = this
            when (page) {
                0 -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 510.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Who’s it with?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enterMotion()) }
                    item { Text("Choose a saved person to record this transaction.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item {
                        OutlinedTextField(query, { query = it.take(60) }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search people") }, leadingIcon = { LineIcon(Glyph.SEARCH) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), singleLine = true, shape = DueoDesign.SmallShape)
                    }
                    itemsIndexed(people.filter { it.name.contains(query, true) || it.phone.contains(query) }.chunked(3), key = { _, row -> row.joinToString { it.id } }) { index, row ->
                        Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { p ->
                                val source = remember { MutableInteractionSource() }
                                Column(Modifier.weight(1f).pressMotion(source).clip(DueoDesign.SmallShape)
                                    .clickable(interactionSource = source, indication = null, role = Role.Button) { selectedId = p.id; focus.clearFocus(); step = 1 }
                                    .padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Avatar(p.name, Modifier.sharedElement(rememberSharedContentState("person-${p.id}"), transitionScope).size(60.dp), p.photoPath)
                                    Text(p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (p.phone.isNotBlank()) Text(p.phone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (people.isEmpty()) item { Text("No people yet. Add a person before recording money.") }
                    item { OutlinedButton(onClick = { focus.clearFocus(); onAddPerson() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Add person") } }

                }
                1 -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Surface(onClick = { step = 0 }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.enterMotion()) {
                            Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Avatar(canonical, Modifier.sharedElement(rememberSharedContentState("person-$selectedId"), transitionScope).size(32.dp), selected?.photoPath); Text(canonical, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    item {
                        AnimatedContent(amount.ifEmpty { "0" }, transitionSpec = {
                            (fadeIn(tween(160)) + slideInVertically(tween(260, easing = DueoMotion.Ease)) { it / 5 }) togetherWith
                                (fadeOut(tween(90)) + slideOutVertically(tween(180, easing = DueoMotion.Ease)) { -it / 5 })
                        }, label = "keypad digits", modifier = Modifier.padding(vertical = 30.dp).animateContentSize()) { digits ->
                            Text("₹$digits", fontSize = if (digits.length > 9) 32.sp else 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                        }
                    }
                    item { Text(when (initialKind) {
                        EntryKind.RECEIVED -> "To receive ${money(balance.receive)}"
                        EntryKind.PAID -> "To pay ${money(balance.pay)}"
                        else -> "INR · Indian rupees"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "⌫")).forEachIndexed { index, row ->
                                Row(Modifier.fillMaxWidth().enterMotion(index + 1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { key -> KeypadKey(key, Modifier.weight(1f)) { amount = editAmount(amount, key); attempted = false } }
                                }
                            }
                        }
                    }
                    item {
                        AnimatedVisibility(attempted && error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item { Button(onClick = { attempted = true; if (error == null) step = 2 }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Review transaction") } }
                }
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    item { Avatar(canonical, Modifier.sharedElement(rememberSharedContentState("person-$selectedId"), transitionScope).size(64.dp), selected?.photoPath) }
                    item { Text("Looking good?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.enterMotion(1)) }
                    item {
                        Surface(shape = DueoDesign.CardShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().enterMotion(2)) {
                            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(canonical, style = MaterialTheme.typography.titleLarge)
                                Text(money(parsed ?: 0), style = MaterialTheme.typography.headlineLarge)
                                Text(initialKind.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item { OutlinedTextField(note, { note = it.take(240) }, modifier = Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, enabled = !saving, maxLines = 3, shape = DueoDesign.SmallShape) }
                    item {
                        Button(onClick = { if (!saving && error == null && parsed != null) onSave(selectedId, parsed, initialKind, note.trim()) },
                            enabled = !saving && error == null, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                            AnimatedContent(saving, label = "save state") { busy ->
                                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Saving…")
                                } else Text("Save transaction")
                            }
                        }
                    }
                    if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        }
    }
}

internal fun editAmount(current: String, key: String): String {
    if (key == "⌫") return current.dropLast(1)
    if (key == ".") return if ('.' in current) current else current.ifEmpty { "0" } + "."
    if (key.length != 1 || key[0] !in '0'..'9') return current
    val next = if (current == "0") key else current + key
    if (next.substringBefore('.').length > 9 || ('.' in next && next.substringAfter('.').length > 2)) return current
    return next
}

@Composable
private fun KeypadKey(key: String, modifier: Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.pressMotion(source).clip(CircleShape).background(MaterialTheme.colorScheme.background)
        .semantics { contentDescription = if (key == "⌫") "Delete digit" else if (key == ".") "Decimal point" else "Digit $key" }
        .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick).heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
        Text(key, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
    }
}
