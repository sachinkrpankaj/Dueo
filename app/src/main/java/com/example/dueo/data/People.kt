package com.example.dueo.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Person(val id: String = UUID.randomUUID().toString(), val name: String, val phone: String = "", val photoPath: String? = null)
data class LedgerState(val people: List<Person> = emptyList(), val entries: List<Entry> = emptyList())

/** People and entries commit together. The original v1 ledger remains untouched after migration. */
class LedgerStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "ledger-v2.json"))
    fun readState(): LedgerState {
        val raw = try { file.openRead().bufferedReader().use { it.readText() } }
            catch (_: java.io.FileNotFoundException) { return migrate() }
        val root = JSONObject(raw)
        require(root.getInt("version") == 2) { "Unsupported ledger version" }
        val people = root.getJSONArray("people")
        val entries = root.getJSONArray("entries")
        return LedgerState(List(people.length()) { i -> people.getJSONObject(i).let {
            Person(it.getString("id"), it.getString("name"), it.optString("phone"), it.optString("photoPath").takeIf(String::isNotBlank))
        } }, List(entries.length()) { i -> parseEntry(entries.getJSONObject(i)) })
    }
    private fun parseEntry(row: JSONObject) = Entry(row.getString("id"), row.getString("person"), row.getLong("amount"),
        EntryKind.valueOf(row.getString("kind")), row.getString("note"), row.getLong("date"), row.optString("personId"))
    private fun migrate(): LedgerState {
        val legacy = AtomicFile(File(context.filesDir, "ledger-v1.json"))
        val raw = try { legacy.openRead().bufferedReader().use { it.readText() } }
            catch (_: java.io.FileNotFoundException) { return LedgerState() }
        val rows = JSONArray(raw)
        val migrated = migrateEntries(List(rows.length()) { parseEntry(rows.getJSONObject(it)) })
        writeState(migrated)
        return migrated
    }
    fun writeState(state: LedgerState) {
        require(state.people.all { it.name.isNotBlank() })
        require(state.people.map { it.id }.distinct().size == state.people.size)
        require(state.entries.all { entry -> state.people.any { it.id == entry.personId } })
        val people = JSONArray()
        state.people.forEach { p -> people.put(JSONObject().apply {
            put("id", p.id); put("name", p.name.trim()); put("phone", p.phone); put("photoPath", p.photoPath.orEmpty())
        }) }
        val entries = JSONArray()
        state.entries.forEach { e -> entries.put(JSONObject().apply {
            put("id", e.id); put("person", e.person); put("personId", e.personId); put("amount", e.amount)
            put("kind", e.kind.name); put("note", e.note); put("date", e.date)
        }) }
        val root = JSONObject().put("version", 2).put("people", people).put("entries", entries)
        val stream = file.startWrite()
        try { stream.write(root.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun addPerson(state: LedgerState, name: String, phone: String, croppedPath: String?): LedgerState {
        require(name.trim().isNotEmpty())
        val id = UUID.randomUUID().toString()
        val photo = croppedPath?.let { source ->
            val folder = File(context.filesDir, "people-photos").apply { mkdirs() }
            File(folder, "$id.jpg").also { File(source).copyTo(it) }
        }
        val updated = state.copy(people = state.people + Person(id, name.trim(), phone.trim(), photo?.absolutePath))
        try { writeState(updated) } catch (error: Exception) { photo?.delete(); throw error }
        return updated
    }
}

fun migrateEntries(entries: List<Entry>): LedgerState {
    val people = entries.map { it.person }.distinct().map { Person(name = it) }
    return LedgerState(people, entries.map { e -> e.copy(personId = people.first { it.name == e.person }.id) })
}
