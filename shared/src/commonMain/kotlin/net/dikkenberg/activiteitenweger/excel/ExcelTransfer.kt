// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.excel

import com.gyanoba.kexcel.Excel
import com.gyanoba.kexcel.sheet.CellIndex
import com.gyanoba.kexcel.sheet.CellStyle
import com.gyanoba.kexcel.sheet.CellValue
import com.gyanoba.kexcel.sheet.DateTimeCellValue
import com.gyanoba.kexcel.sheet.DoubleCellValue
import com.gyanoba.kexcel.sheet.FormulaCellValue
import com.gyanoba.kexcel.sheet.IntCellValue
import com.gyanoba.kexcel.sheet.TextCellValue
import com.gyanoba.kexcel.sheet.TimeCellValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.dikkenberg.activiteitenweger.model.ActivityCategory
import net.dikkenberg.activiteitenweger.model.ActivityItem
import kotlin.math.roundToInt
import kotlin.time.Instant

data class ExcelImportedActivity(
    val description: String,
    val category: ActivityCategory,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
)

data class ExcelImportResult(
    val activities: List<ExcelImportedActivity>,
    val skippedRows: Int,
    val ignoredSheets: Int,
)

object ExcelTransfer {
    private val headers = listOf(
        "Starttijd",
        "Eindtijd",
        "Activiteit",
        "Minuten",
        "Categorie",
        "Punten per activiteit",
        "Punten totaal",
    )

    private val monthNames = listOf(
        "januari",
        "februari",
        "maart",
        "april",
        "mei",
        "juni",
        "juli",
        "augustus",
        "september",
        "oktober",
        "november",
        "december",
    )

    fun exportWorkbook(activities: List<ActivityItem>): ByteArray {
        val excel = Excel.createExcel()
        val timeZone = TimeZone.currentSystemDefault()
        val ordered = activities.sortedBy { it.payload.startedAt }
        val grouped = ordered.groupBy {
            Instant.parse(it.payload.startedAt).toLocalDateTime(timeZone).date
        }.toSortedMap()

        if (grouped.isEmpty()) {
            excel.rename("Sheet1", "Instellingen")
            writeSettings(excel["Instellingen"])
            excel.setDefaultSheet("Instellingen")
            return requireNotNull(excel.encode()) { "Excel-bestand kon niet worden gemaakt" }
        }

        val firstDate = grouped.keys.first()
        val firstSheetName = sheetName(firstDate)
        excel.rename("Sheet1", firstSheetName)

        grouped.entries.forEachIndexed { index, (date, items) ->
            val name = sheetName(date)
            val sheet = if (index == 0) excel[firstSheetName] else excel[name]
            writeDaySheet(sheet, items, timeZone)
        }

        writeSettings(excel["Instellingen"])
        excel.setDefaultSheet(firstSheetName)
        return requireNotNull(excel.encode()) { "Excel-bestand kon niet worden gemaakt" }
    }

    fun importWorkbook(bytes: ByteArray, fallbackYear: Int): ExcelImportResult {
        require(fallbackYear in 1900..2200) { "Ongeldig jaartal: $fallbackYear" }

        val excel = Excel.decodeBytes(bytes)
        val imported = mutableListOf<ExcelImportedActivity>()
        var skippedRows = 0
        var ignoredSheets = 0

        for ((sheetName, sheet) in excel.getSheets()) {
            if (sheetName.equals("Instellingen", ignoreCase = true)) continue

            val date = parseSheetDate(sheetName, fallbackYear)
            if (date == null) {
                ignoredSheets++
                continue
            }

            val rows = sheet.rows
            if (rows.isEmpty()) continue

            val header = rows.first()
            val headerValues = header.map { it?.value }
            val startColumn = findHeaderColumn(headerValues, "starttijd")
            val endColumn = findHeaderColumn(headerValues, "eindtijd")
            val descriptionColumn = findHeaderColumn(headerValues, "activiteit")
            val categoryColumn = findHeaderColumn(headerValues, "categorie")

            if (startColumn == null || endColumn == null || descriptionColumn == null || categoryColumn == null) {
                ignoredSheets++
                continue
            }

            for (row in rows.drop(1)) {
                val startValue = row.getOrNull(startColumn)?.value
                val endValue = row.getOrNull(endColumn)?.value
                val descriptionValue = row.getOrNull(descriptionColumn)?.value
                val categoryValue = row.getOrNull(categoryColumn)?.value

                val description = cellText(descriptionValue).trim()
                val categoryText = cellText(categoryValue).trim()
                val startTime = cellTime(startValue)
                val endTime = cellTime(endValue)

                val hasAnyInput = description.isNotBlank() ||
                    categoryText.isNotBlank() ||
                    cellText(startValue).isNotBlank() ||
                    cellText(endValue).isNotBlank()
                if (!hasAnyInput) continue

                val category = categoryFromText(categoryText)
                if (description.isBlank() || category == null || startTime == null || endTime == null) {
                    skippedRows++
                    continue
                }

                val endDate = if (endTime < startTime) LocalDate.fromEpochDays(date.toEpochDays() + 1) else date
                imported += ExcelImportedActivity(
                    description = description,
                    category = category,
                    startDate = date,
                    startTime = startTime,
                    endDate = endDate,
                    endTime = endTime,
                )
            }
        }

        return ExcelImportResult(
            activities = imported,
            skippedRows = skippedRows,
            ignoredSheets = ignoredSheets,
        )
    }

    private fun writeDaySheet(
        sheet: com.gyanoba.kexcel.sheet.Sheet,
        items: List<ActivityItem>,
        timeZone: TimeZone,
    ) {
        val headerStyle = CellStyle(bold = true)
        headers.forEachIndexed { column, header ->
            sheet.updateCell(
                CellIndex.indexByColumnRow(columnIndex = column, rowIndex = 0),
                TextCellValue(header),
                cellStyle = headerStyle,
            )
        }

        items.forEachIndexed { index, item ->
            val rowIndex = index + 1
            val excelRow = rowIndex + 1
            val start = Instant.parse(item.payload.startedAt).toLocalDateTime(timeZone)
            val end = item.payload.endedAt
                ?.let(Instant::parse)
                ?.toLocalDateTime(timeZone)

            sheet.updateCell(
                CellIndex.indexByColumnRow(0, rowIndex),
                TimeCellValue(start.hour, start.minute, start.second),
            )
            if (end != null) {
                sheet.updateCell(
                    CellIndex.indexByColumnRow(1, rowIndex),
                    TimeCellValue(end.hour, end.minute, end.second),
                )
            }
            sheet.updateCell(
                CellIndex.indexByColumnRow(2, rowIndex),
                TextCellValue(item.payload.description),
            )
            if (end != null) {
                sheet.updateCell(
                    CellIndex.indexByColumnRow(3, rowIndex),
                    FormulaCellValue(
                        "=IF(OR(A$excelRow=\"\",B$excelRow=\"\"),\"\",MOD(B$excelRow-A$excelRow,1)*1440)"
                    ),
                )
            }
            sheet.updateCell(
                CellIndex.indexByColumnRow(4, rowIndex),
                TextCellValue(item.payload.category.label),
            )
            if (end != null) {
                sheet.updateCell(
                    CellIndex.indexByColumnRow(5, rowIndex),
                    FormulaCellValue(
                        "=IF(OR(D$excelRow=\"\",D$excelRow<=0,E$excelRow=\"\"),\"\",D$excelRow/30*VLOOKUP(E$excelRow,'Instellingen'!\$A\$2:\$B\$5,2,FALSE))"
                    ),
                )
                sheet.updateCell(
                    CellIndex.indexByColumnRow(6, rowIndex),
                    FormulaCellValue("=IF(F$excelRow=\"\",\"\",SUM(\$F\$2:F$excelRow))"),
                )
            }
        }

        sheet.setColumnWidth(0, 12.0)
        sheet.setColumnWidth(1, 12.0)
        sheet.setColumnWidth(2, 34.0)
        sheet.setColumnWidth(3, 11.0)
        sheet.setColumnWidth(4, 16.0)
        sheet.setColumnWidth(5, 20.0)
        sheet.setColumnWidth(6, 15.0)
    }

    private fun writeSettings(sheet: com.gyanoba.kexcel.sheet.Sheet) {
        val headerStyle = CellStyle(bold = true)
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("Categorie"), headerStyle)
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Punten per 30 min"), headerStyle)

        ActivityCategory.entries.forEachIndexed { index, category ->
            val row = index + 1
            sheet.updateCell(
                CellIndex.indexByColumnRow(0, row),
                TextCellValue(category.label),
            )
            sheet.updateCell(
                CellIndex.indexByColumnRow(1, row),
                DoubleCellValue(category.pointsPer30Minutes),
            )
        }

        sheet.updateCell(CellIndex.indexByString("A7"), TextCellValue("Berekening"), headerStyle)
        sheet.updateCell(
            CellIndex.indexByString("B7"),
            TextCellValue("Minuten / 30 × punten van de categorie"),
        )
        sheet.updateCell(CellIndex.indexByString("A8"), TextCellValue("Opmerking"), headerStyle)
        sheet.updateCell(
            CellIndex.indexByString("B8"),
            TextCellValue("Minuten en punten op dagbladen worden met formules berekend."),
        )

        sheet.setColumnWidth(0, 24.0)
        sheet.setColumnWidth(1, 52.0)
    }

    private fun sheetName(date: LocalDate): String =
        "${date.day} ${monthNames[date.month.ordinal]} ${date.year}"

    private fun parseSheetDate(name: String, fallbackYear: Int): LocalDate? {
        val trimmed = name.trim()

        runCatching { LocalDate.parse(trimmed) }.getOrNull()?.let { return it }

        val dutch = Regex(
            """(?i)^(?:[a-zà-ÿ]+\s+)?(\d{1,2})\s+""" +
                """(januari|februari|maart|april|mei|juni|juli|augustus|september|oktober|november|december)""" +
                """(?:\s+(\d{4}))?$"""
        ).matchEntire(trimmed)
        if (dutch != null) {
            val day = dutch.groupValues[1].toInt()
            val month = monthNames.indexOf(dutch.groupValues[2].lowercase()) + 1
            val year = dutch.groupValues[3].toIntOrNull() ?: fallbackYear
            return runCatching { LocalDate(year, month, day) }.getOrNull()
        }

        val numeric = Regex("""^(\d{1,2})[-/](\d{1,2})(?:[-/](\d{4}))?$""").matchEntire(trimmed)
        if (numeric != null) {
            val day = numeric.groupValues[1].toInt()
            val month = numeric.groupValues[2].toInt()
            val year = numeric.groupValues[3].toIntOrNull() ?: fallbackYear
            return runCatching { LocalDate(year, month, day) }.getOrNull()
        }

        return null
    }

    private fun findHeaderColumn(values: List<CellValue?>, wanted: String): Int? =
        values.indexOfFirst { normalizeHeader(cellText(it)) == wanted }
            .takeIf { it >= 0 }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase().replace(" ", "")

    private fun categoryFromText(value: String): ActivityCategory? {
        val normalized = value.trim().lowercase()
        return ActivityCategory.entries.firstOrNull {
            it.label.lowercase() == normalized || it.name.lowercase() == normalized
        }
    }

    private fun cellText(value: CellValue?): String = when (value) {
        null -> ""
        is TextCellValue -> value.value.toString()
        is TimeCellValue -> value.toString()
        is DateTimeCellValue -> value.toString()
        is IntCellValue -> value.value.toString()
        is DoubleCellValue -> value.value.toString()
        else -> value.toString()
    }

    private fun cellTime(value: CellValue?): LocalTime? = when (value) {
        is TimeCellValue -> safeTime(value.hour, value.minute, value.second)
        is DateTimeCellValue -> safeTime(value.hour, value.minute, value.second)
        is TextCellValue -> parseTextTime(value.value.toString())
        is DoubleCellValue -> timeFromFraction(value.value)
        is IntCellValue -> timeFromFraction(value.value.toDouble())
        else -> null
    }

    private fun parseTextTime(value: String): LocalTime? {
        val parts = value.trim().split(":")
        if (parts.size !in 2..3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = parts.getOrNull(2)?.toDoubleOrNull()?.roundToInt() ?: 0
        return safeTime(hour, minute, second)
    }

    private fun timeFromFraction(value: Double): LocalTime? {
        val fraction = value - kotlin.math.floor(value)
        val seconds = (fraction * 86_400.0).roundToInt().mod(86_400)
        return safeTime(
            hour = seconds / 3600,
            minute = (seconds % 3600) / 60,
            second = seconds % 60,
        )
    }

    private fun safeTime(hour: Int, minute: Int, second: Int): LocalTime? =
        runCatching { LocalTime(hour, minute, second) }.getOrNull()
}
