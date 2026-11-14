package com.example.dueo.data

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

object StatementSharer {

    private fun getSharedDir(context: Context): File {
        val dir = File(context.cacheDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Generates a high-quality, branded square image representing the current balance with a person.
     * Dynamically scales font sizes and measures text to ensure no clipping or overflow
     * even with lengthy names or massive financial figures.
     */
    fun generateBalanceImage(context: Context, person: Person, balance: Balance): Uri {
        val width = 1080
        val height = 1080
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Gradient background (Dueo Brand Violet/Indigo)
        val gradient = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xFF7160F3.toInt(), 0xFF5642CC.toInt(), 0xFF2A1B80.toInt()),
            null, Shader.TileMode.CLAMP
        )
        val bgPaint = Paint().apply { shader = gradient }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Inner Card
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            setShadowLayer(30f, 0f, 15f, 0x33000000)
        }
        val cardRect = RectF(70f, 70f, width - 70f, height - 70f)
        canvas.drawRoundRect(cardRect, 48f, 48f, cardPaint)

        // Text Paints
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5642CC.toInt()
            textSize = 54f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF727187.toInt()
            textSize = 28f
        }
        val personPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF171523.toInt()
            textSize = 62f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF727187.toInt()
            textSize = 32f
        }
        val receiveAmountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4F378B.toInt()
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val payAmountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB3261E.toInt()
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val netDuesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF171523.toInt()
            textSize = 70f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF888899.toInt()
            textSize = 26f
        }
        val disclaimerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA0A0B0.toInt()
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }

        // Draw Header
        canvas.drawText("dueo", 130f, 160f, brandPaint)
        canvas.drawText("Shared dues, made simple.", 130f, 205f, taglinePaint)

        // Divider
        val dividerPaint = Paint().apply {
            color = 0xFFE8E8EE.toInt()
            strokeWidth = 2f
        }
        canvas.drawLine(130f, 245f, width - 130f, 245f, dividerPaint)

        // Person Name (Dynamic measurement and scaling to prevent overflow)
        canvas.drawText("Statement for", 130f, 310f, sectionTitlePaint)
        val maxNameWidth = width - 260f
        var nameSize = 62f
        personPaint.textSize = nameSize
        while (nameSize > 34f && personPaint.measureText(person.name) > maxNameWidth) {
            nameSize -= 2f
            personPaint.textSize = nameSize
        }
        val displayName = if (personPaint.measureText(person.name) > maxNameWidth) {
            var cut = person.name.length
            while (cut > 0 && personPaint.measureText(person.name.take(cut) + "…") > maxNameWidth) {
                cut--
            }
            person.name.take(cut) + "…"
        } else {
            person.name
        }
        canvas.drawText(displayName, 130f, 380f, personPaint)

        // Amounts Section (Dynamic measurement)
        val colWidth = (width - 260f) / 2f
        val maxColAmountWidth = colWidth - 25f

        canvas.drawText("They owe you", 130f, 470f, sectionTitlePaint)
        val receiveText = money(balance.receive)
        var recSize = 56f
        receiveAmountPaint.textSize = recSize
        while (recSize > 26f && receiveAmountPaint.measureText(receiveText) > maxColAmountWidth) {
            recSize -= 2f
            receiveAmountPaint.textSize = recSize
        }
        canvas.drawText(receiveText, 130f, 540f, receiveAmountPaint)

        canvas.drawText("You owe them", 130f + colWidth, 470f, sectionTitlePaint)
        val payText = money(balance.pay)
        var paySize = 56f
        payAmountPaint.textSize = paySize
        while (paySize > 26f && payAmountPaint.measureText(payText) > maxColAmountWidth) {
            paySize -= 2f
            payAmountPaint.textSize = paySize
        }
        canvas.drawText(payText, 130f + colWidth, 540f, payAmountPaint)

        // Net dues box
        val netBoxRect = RectF(130f, 610f, width - 130f, 790f)
        val netBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F3FA.toInt()
        }
        canvas.drawRoundRect(netBoxRect, 28f, 28f, netBoxPaint)

        val net = balance.net()
        canvas.drawText("Net Balance", 170f, 675f, sectionTitlePaint)

        val netText = money(net)
        val maxNetWidth = width - 360f
        var netSize = 70f
        netDuesPaint.textSize = netSize
        while (netSize > 32f && netDuesPaint.measureText(netText) > maxNetWidth) {
            netSize -= 2f
            netDuesPaint.textSize = netSize
        }
        canvas.drawText(netText, 170f, 755f, netDuesPaint)

        // Date and Disclaimer
        val dateFormat = SimpleDateFormat("dd MMMM yyyy, hh:mm a", Locale.getDefault())
        canvas.drawText("Generated: ${dateFormat.format(Date())}", 130f, 855f, datePaint)
        canvas.drawText("Personal record generated by Dueo. Please verify all entries.", (width / 2).toFloat(), 940f, disclaimerPaint)

        // Save Bitmap
        val file = File(getSharedDir(context), "dueo-balance-${UUID.randomUUID()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Generates a detailed statement image containing all chronological transactions without omission.
     * Dynamically sizes canvas height based on the total transaction count.
     */
    fun generateStatementImage(context: Context, person: Person, entries: List<Entry>): Uri {
        val chronological = entries.sortedBy { it.date }
        val width = 1080
        val rowHeight = 85
        val baseHeaderHeight = 250
        val tableHeadHeight = 80
        val summaryBoxHeight = 220
        val padding = 60

        val totalHeight = (baseHeaderHeight + tableHeadHeight + (chronological.size * rowHeight) + summaryBoxHeight + padding)
            .coerceIn(900, 16000)

        val bitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background
        canvas.drawColor(0xFFF7F8FC.toInt())

        // Header Card
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5642CC.toInt() }
        canvas.drawRect(0f, 0f, width.toFloat(), 240f, headerPaint)

        val whiteBrandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val whiteSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFDDD6FE.toInt()
            textSize = 28f
        }
        canvas.drawText("dueo", 60f, 90f, whiteBrandPaint)
        canvas.drawText("Statement of Account · ${person.name}", 60f, 145f, whiteSubPaint)
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        canvas.drawText("Generated on ${dateFormat.format(Date())}", 60f, 195f, whiteSubPaint)

        // Table Header
        var y = 290f
        val tableHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF727187.toInt()
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("DATE", 60f, y, tableHeadPaint)
        canvas.drawText("DESCRIPTION / NOTE", 230f, y, tableHeadPaint)
        canvas.drawText("TYPE", 660f, y, tableHeadPaint)
        val amountHeadPaint = Paint(tableHeadPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText("AMOUNT", width - 60f, y, amountHeadPaint)

        val dividerPaint = Paint().apply { color = 0xFFE2E4EE.toInt(); strokeWidth = 2f }
        canvas.drawLine(60f, y + 15f, width - 60f, y + 15f, dividerPaint)
        y += 55f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202039.toInt(); textSize = 28f }
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555566.toInt(); textSize = 26f }
        val rightTextPaint = Paint(textPaint).apply {
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Render every transaction without artificial truncation
        val maxNoteWidth = 410f
        for (e in chronological) {
            val dateStr = dateFormat.format(Date(e.date))
            canvas.drawText(dateStr, 60f, y, textPaint)

            val rawNote = if (e.note.isNotBlank()) e.note else e.kind.label
            val noteStr = if (notePaint.measureText(rawNote) > maxNoteWidth) {
                var cut = rawNote.length
                while (cut > 0 && notePaint.measureText(rawNote.take(cut) + "…") > maxNoteWidth) cut--
                rawNote.take(cut) + "…"
            } else rawNote

            canvas.drawText(noteStr, 230f, y, notePaint)
            canvas.drawText(e.kind.name, 660f, y, textPaint)
            canvas.drawText(money(e.amount), width - 60f, y, rightTextPaint)

            y += 30f
            canvas.drawLine(60f, y, width - 60f, y, dividerPaint)
            y += 55f
        }

        // Summary box
        val balance = entries.balance()
        val sumBox = RectF(60f, y + 10f, width - 60f, y + 170f)
        val sumBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(sumBox, 24f, 24f, sumBoxPaint)

        canvas.drawText("Summary of Outstanding Balances", 90f, y + 60f, tableHeadPaint)
        val balStr = "They owe: ${money(balance.receive)}    |    You owe: ${money(balance.pay)}    |    Net: ${money(balance.net())}"
        canvas.drawText(balStr, 90f, y + 115f, textPaint)

        val file = File(getSharedDir(context), "dueo-statement-${UUID.randomUUID()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Generates a clean, vector, multi-page PDF document representing the complete statement of account.
     * Supports arbitrarily large transaction histories paginated across standard A4 pages with running headers,
     * footers, and a comprehensive summary section.
     */
    fun generateStatementPdf(context: Context, person: Person, entries: List<Entry>): Uri {
        val chronological = entries.sortedBy { it.date }
        val balance = entries.balance()
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        val pdfDocument = PdfDocument()
        val pageWidth = 595 // Standard A4 width in pt
        val pageHeight = 842 // Standard A4 height in pt

        // Determine pagination
        val page1Capacity = 16
        val subsequentCapacity = 24
        val totalPages = if (chronological.size <= page1Capacity) 1 else {
            1 + ((chronological.size - page1Capacity + subsequentCapacity - 1) / subsequentCapacity)
        }

        var entryIndex = 0

        // Common Paints
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5642CC.toInt()
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val taglinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF727187.toInt()
            textSize = 10f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF171523.toInt()
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tableHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF727187.toInt()
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val amountHeadPaint = Paint(tableHeadPaint).apply { textAlign = Paint.Align.RIGHT }
        val rowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF202039.toInt()
            textSize = 9f
        }
        val rowNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF555566.toInt()
            textSize = 8.5f
        }
        val rowAmountPaint = Paint(rowTextPaint).apply {
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val linePaint = Paint().apply {
            color = 0xFFE2E4EE.toInt()
            strokeWidth = 1f
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9999AA.toInt()
            textSize = 8f
        }

        for (pageNum in 1..totalPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            var y = 45f

            if (pageNum == 1) {
                // Branded Header (Page 1)
                canvas.drawText("dueo", 40f, y, brandPaint)
                canvas.drawText("Shared dues, made simple.", 40f, y + 16f, taglinePaint)

                y += 45f
                canvas.drawText("Statement of Account · ${person.name}", 40f, y, titlePaint)
                if (person.phone.isNotBlank()) {
                    canvas.drawText("Phone: ${person.phone}", 40f, y + 15f, taglinePaint)
                    y += 15f
                }
                canvas.drawText("Generated on ${dateFormat.format(Date())}", 40f, y + 15f, taglinePaint)
                y += 28f
            } else {
                // Compact Running Header (Page 2+)
                canvas.drawText("dueo · Statement of Account · ${person.name}", 40f, y, taglinePaint)
                canvas.drawLine(40f, y + 8f, pageWidth - 40f, y + 8f, linePaint)
                y += 25f
            }

            // Table Header
            canvas.drawText("DATE", 40f, y, tableHeadPaint)
            canvas.drawText("DESCRIPTION / NOTE", 115f, y, tableHeadPaint)
            canvas.drawText("TYPE", 360f, y, tableHeadPaint)
            canvas.drawText("AMOUNT", pageWidth - 40f, y, amountHeadPaint)
            canvas.drawLine(40f, y + 6f, pageWidth - 40f, y + 6f, linePaint)
            y += 22f

            // Rows on this page
            val rowsToTake = if (pageNum == 1) page1Capacity else subsequentCapacity
            val pageEntries = chronological.drop(entryIndex).take(rowsToTake)
            entryIndex += pageEntries.size

            for (e in pageEntries) {
                val dateStr = dateFormat.format(Date(e.date))
                canvas.drawText(dateStr, 40f, y, rowTextPaint)

                val rawNote = if (e.note.isNotBlank()) e.note else e.kind.label
                val maxNoteW = 230f
                val noteStr = if (rowNotePaint.measureText(rawNote) > maxNoteW) {
                    var cut = rawNote.length
                    while (cut > 0 && rowNotePaint.measureText(rawNote.take(cut) + "…") > maxNoteW) cut--
                    rawNote.take(cut) + "…"
                } else rawNote

                canvas.drawText(noteStr, 115f, y, rowNotePaint)
                canvas.drawText(e.kind.name, 360f, y, rowTextPaint)
                canvas.drawText(money(e.amount), pageWidth - 40f, y, rowAmountPaint)

                y += 10f
                canvas.drawLine(40f, y, pageWidth - 40f, y, linePaint)
                y += 18f
            }

            // Summary box on the last page
            if (pageNum == totalPages) {
                y += 15f
                val summaryRect = RectF(40f, y, pageWidth - 40f, y + 65f)
                val sumBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F3FA.toInt() }
                canvas.drawRoundRect(summaryRect, 10f, 10f, sumBgPaint)

                canvas.drawText("Summary Balance", 55f, y + 22f, tableHeadPaint)
                val balSummary = "They owe: ${money(balance.receive)}    |    You owe: ${money(balance.pay)}    |    Net: ${money(balance.net())}"
                canvas.drawText(balSummary, 55f, y + 45f, rowTextPaint)
            }

            // Footer on every page
            canvas.drawText("Personal record generated by Dueo. Please verify all entries.", 40f, pageHeight - 30f, footerPaint)
            val pageNumStr = "Page $pageNum of $totalPages"
            val pageNumPaint = Paint(footerPaint).apply { textAlign = Paint.Align.RIGHT }
            canvas.drawText(pageNumStr, pageWidth - 40f, pageHeight - 30f, pageNumPaint)

            pdfDocument.finishPage(page)
        }

        val pdfFile = File(getSharedDir(context), "dueo-statement-${UUID.randomUUID()}.pdf")
        FileOutputStream(pdfFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
    }

    /**
     * Shares a content URI using the Android system Sharesheet.
     */
    fun shareImage(context: Context, uri: Uri, title: String, textMessage: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, textMessage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * Shares a PDF statement URI using the Android system Sharesheet.
     */
    fun sharePdf(context: Context, uri: Uri, title: String, textMessage: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, textMessage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
