package com.example.dueo.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.util.UUID

enum class EntryKind(val label: String) {
    LENT("Money lent"), BORROWED("Money borrowed"), RECEIVED("Repayment received"), PAID("Repayment made")
}
data class Entry(
    val id: String = UUID.randomUUID().toString(), val person: String,
    val amount: Long, val kind: EntryKind, val note: String = "",
    val date: Long = System.currentTimeMillis(), val personId: String = ""
)
data class Balance(val receive: Long = 0, val pay: Long = 0)
fun List<Entry>.balance(): Balance = fold(Balance()) { b, e ->
    when (e.kind) {
        EntryKind.LENT -> b.copy(receive = Math.addExact(b.receive, e.amount))
        EntryKind.RECEIVED -> b.copy(receive = Math.subtractExact(b.receive, e.amount))
        EntryKind.BORROWED -> b.copy(pay = Math.addExact(b.pay, e.amount))
        EntryKind.PAID -> b.copy(pay = Math.subtractExact(b.pay, e.amount))
    }
}
fun parseAmount(text: String): Long? {
    if (!Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(text)) return null
    return runCatching { BigDecimal(text).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it > 0 }
}
fun money(paise: Long): String {
    val parts = BigDecimal.valueOf(paise, 2).abs().toPlainString().split('.')
    val whole = parts[0]
    val grouped = if (whole.length <= 3) whole else
        whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
    return (if (paise < 0) "−" else "") + "₹" + grouped + "." + parts[1]
}

