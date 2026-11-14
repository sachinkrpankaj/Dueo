package com.example.dueo.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dueo.data.*
import com.example.dueo.ui.theme.*

internal data class PersonSummary(val name: String, val balance: Balance, val count: Int, val id: String = name, val phone: String = "", val photoPath: String? = null)
internal fun List<Entry>.people(registry: List<Person> = map { it.person }.distinct().map { Person(id = it, name = it) }): List<PersonSummary> =
    registry.map { person ->
        val records = filter { it.personId == person.id || (it.personId.isEmpty() && it.person == person.name) }
        PersonSummary(person.name, records.balance(), records.size, person.id, person.phone, person.photoPath)
    }.sortedByDescending { it.count }

@Composable
internal fun HomeScreen(entries: List<Entry>, dark: Boolean, onTheme: () -> Unit, onAdd: (EntryKind?) -> Unit, onPeople: () -> Unit, onPerson: (String) -> Unit, registry: List<Person>? = null, onAddPerson: () -> Unit = { onAdd(EntryKind.LENT) }) {
    val people = remember(entries, registry) { if (registry == null) entries.people() else entries.people(registry) }
    LazyColumn(Modifier.background(Color(0xFF111318)), contentPadding = PaddingValues(bottom = 0.dp)) {
        item {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFD3C9FF), onPrimary = Color(0xFF171523),
                background = Color(0xFF111318), surface = Color(0xFF24262C),
                surfaceVariant = Color(0xFF30313B), onSurface = Color(0xFFF8F8FC), onSurfaceVariant = Color(0xFFB4B5C1)
            ), typography = Typography, shapes = DueoDesign.Shapes) {
                Column(Modifier.fillMaxWidth().background(Color(0xFF111318)).padding(horizontal = DueoDesign.PagePadding).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandHeader(dark, onTheme, onDarkSurface = true)
                    BalanceOverview(entries.balance(), alignedStart = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction("Lend", Glyph.LEND, false, Modifier.weight(1f)) { onAdd(EntryKind.LENT) }
                        QuickAction("Add", Glyph.ADD, true, Modifier.weight(1f)) { onAdd(null) }
                        QuickAction("Borrow", Glyph.BORROW, false, Modifier.weight(1f)) { onAdd(EntryKind.BORROWED) }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.enterMotion(4).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Frequent people", "See all", onPeople)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (people.isEmpty()) item {
                            FeaturedCard(null, onAddPerson)
                        } else items(people.take(8), key = { it.id }) { person -> FeaturedCard(person) { onPerson(person.id) } }
                    }
                    Spacer(Modifier.height(6.dp))
                    SectionTitle("Your people", if (people.isNotEmpty()) "${people.size} people" else null, onPeople)
                    if (people.isEmpty()) {
                        Row(Modifier.padding(horizontal = 22.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            LineIcon(Glyph.PEOPLE, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column {
                                Text("No dues yet", style = MaterialTheme.typography.titleMedium)
                                Text("Your people and balances will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else people.take(4).forEach { person ->
                        PersonRow(person, Modifier.padding(horizontal = 20.dp)) { onPerson(person.id) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BalanceOverview(balance: Balance, alignedStart: Boolean = false) {
    val formatted = money(balance.receive - balance.pay)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.enterMotion(1).fillMaxWidth().padding(top = 15.dp, bottom = 12.dp), horizontalAlignment = if (alignedStart) Alignment.Start else Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Net dues", style = MaterialTheme.typography.bodyMedium, color = muted)
        AnimatedContent(formatted, transitionSpec = {
            (slideInVertically(tween(420)) { it / 2 } + fadeIn(tween(300))) togetherWith
                (slideOutVertically(tween(240)) { -it / 2 } + fadeOut(tween(160)))
        }, label = "dues change") { displayed ->
        val decimal = displayed.lastIndexOf('.')
        Text(buildAnnotatedString {
            if (decimal >= 0) {
                append(displayed.substring(0, decimal))
                withStyle(SpanStyle(color = muted, fontSize = 21.sp)) { append(displayed.substring(decimal)) }
            } else append(displayed)
        }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineLarge, fontSize = if (formatted.length > 14) 27.sp else if (formatted.length > 11) 32.sp else if (alignedStart) 36.sp else 40.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Column(Modifier.weight(1f), horizontalAlignment = if (alignedStart) Alignment.Start else Alignment.CenterHorizontally) {
                Text("To receive", style = MaterialTheme.typography.labelSmall, color = muted)
                Text(money(balance.receive), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Box(Modifier.padding(top = 3.dp, end = if (alignedStart) 16.dp else 0.dp).height(28.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.weight(1f), horizontalAlignment = if (alignedStart) Alignment.Start else Alignment.CenterHorizontally) {
                Text("To pay", style = MaterialTheme.typography.labelSmall, color = muted)
                Text(money(balance.pay), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp).heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeaturedCard(person: PersonSummary?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .965f else 1f, spring(dampingRatio = .85f, stiffness = 600f), label = "person press")
    Column(Modifier.enterMotion(2).width(210.dp).scale(scale).clip(DueoDesign.SmallShape).background(DueoDesign.Gradient)
        .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).background(Color.White.copy(alpha = .17f), CircleShape), contentAlignment = Alignment.Center) {
                if (person == null) LineIcon(Glyph.ADD, tint = Color.White)
                else Avatar(person.name, photoPath = person.photoPath)
            }
            Text(person?.name ?: "Add your first person", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (person == null) {
            Text("Shared dues,\nmade simple.", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 27.sp)
            Text("Create their profile", color = Color.White, style = MaterialTheme.typography.labelSmall)
        } else {
            val b = person.balance
            val receivable = b.receive >= b.pay
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (person.count == 0) "No transactions yet" else if (b.receive == 0L && b.pay == 0L) "All settled" else if (receivable) "Owes you" else "You owe", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(money(if (receivable) b.receive else b.pay), color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(if (b.receive > 0 && b.pay > 0) "Also ${if (receivable) "to pay" else "to receive"} ${money(if (receivable) b.pay else b.receive)}" else "View ledger", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun PersonRow(person: PersonSummary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(onClick = onClick, interactionSource = source, modifier = modifier.enterMotion(1).pressMotion(source).animateContentSize().fillMaxWidth(), shape = DueoDesign.SmallShape, color = MaterialTheme.colorScheme.background) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(person.name, photoPath = person.photoPath)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(person.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (person.phone.isNotBlank()) Text(person.phone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (person.balance.receive == 0L && person.balance.pay == 0L) Text(if (person.count == 0) "No transactions yet" else "All settled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (person.balance.receive > 0) BalanceLine("Owes you", person.balance.receive, MaterialTheme.colorScheme.primary)
                if (person.balance.pay > 0) BalanceLine("You owe", person.balance.pay, MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun BalanceLine(label: String, amount: Long, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(money(amount), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Preview(name = "Home · light", widthDp = 393, heightDp = 820, showBackground = true)
@Composable
private fun LightHomePreview() { DashboardPreview(false) }
@Preview(name = "Home · dark", widthDp = 393, heightDp = 820, showBackground = true)
@Composable
private fun DarkHomePreview() { DashboardPreview(true) }
@Preview(name = "Empty · dark", widthDp = 393, heightDp = 820, showBackground = true)
@Composable
private fun EmptyPreview() { DueoTheme(true) { Surface { HomeScreen(emptyList(), true, {}, {}, {}, {}) } } }
@Composable
private fun DashboardPreview(dark: Boolean) {
    val samples = listOf(Entry(person = "Rahul Kumar", amount = 120000, kind = EntryKind.LENT), Entry(person = "Neha Sharma", amount = 50000, kind = EntryKind.BORROWED))
    DueoTheme(dark) { Scaffold(bottomBar = { PillNavigation(0) {} }) { inset -> Box(Modifier.padding(inset)) { HomeScreen(samples, dark, {}, {}, {}, {}) } } }
}
