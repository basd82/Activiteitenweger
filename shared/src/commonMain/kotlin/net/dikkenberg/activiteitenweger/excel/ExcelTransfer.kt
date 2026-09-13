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

    fun exportWorkbook(
        activities: List<ActivityItem>,
        categories: List<ActivityCategory> = ActivityCategory.defaults,
    ): ByteArray {
        val excel = Excel.createExcel()
        val timeZone = TimeZone.currentSystemDefault()
        val ordered = activities.sortedBy { it.payload.startedAt }
        val grouped = ordered.groupBy {
            Instant.parse(it.payload.startedAt).toLocalDateTime(timeZone).date
        }.entries.sortedBy { it.key }

        if (grouped.isEmpty()) {
            excel.rename("Sheet1", "Instellingen")
            writeSettings(excel["Instellingen"], categories)
            excel.setDefaultSheet("Instellingen")
            return requireNotNull(excel.encode()) { "Excel-bestand kon niet worden gemaakt" }
        }

        val firstDate = grouped.first().key
        val firstSheetName = sheetName(firstDate)
        excel.rename("Sheet1", firstSheetName)

        grouped.forEachIndexed { index, entry ->
            val date = entry.key
            val items = entry.value
            val name = sheetName(date)
            val sheet = if (index == 0) excel[firstSheetName] else excel[name]
            writeDaySheet(sheet, items, timeZone)
        }

        writeSettings(excel["Instellingen"], categories)
        excel.setDefaultSheet(firstSheetName)
        return requireNotNull(excel.encode()) { "Excel-bestand kon niet worden gemaakt" }
    }

    fun importWorkbook(
        bytes: ByteArray,
        fallbackYear: Int,
        categories: List<ActivityCategory> = ActivityCategory.defaults,
    ): ExcelImportResult {
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

            val headerValues = rows.first().map { it?.value }
            val startColumn = findHeaderColumn(headerValues, "starttijd")
            val endColumn = findHeaderColumn(headerValues, "eindtijd")
            val descriptionColumn = findHeaderColumn(headerValues, "activiteit")
            val categoryColumn = findHeaderColumn(headerValues, "categorie")

            if (
                startColumn != null &&
                endColumn != null &&
                descriptionColumn != null &&
                categoryColumn != null
            ) {
                skippedRows += importCurrentDaySheet(
                    rows = rows,
                    date = date,
                    startColumn = startColumn,
                    endColumn = endColumn,
                    descriptionColumn = descriptionColumn,
                    categoryColumn = categoryColumn,
                    categories = categories,
                    destination = imported,
                )
                continue
            }

            val timeColumn = findHeaderColumn(headerValues, "tijdstip")
            val legacyDescriptionColumn = findHeaderColumn(headerValues, "activiteit")
            val legacyCategoryColumns = headerValues.mapIndexedNotNull { index, value ->
                categoryFromText(cellText(value), categories)?.let { category -> index to category }
            }

            if (
                timeColumn != null &&
                legacyDescriptionColumn != null &&
                legacyCategoryColumns.isNotEmpty()
            ) {
                skippedRows += importLegacyDaySheet(
                    rows = rows,
                    date = date,
                    timeColumn = timeColumn,
                    descriptionColumn = legacyDescriptionColumn,
                    categoryColumns = legacyCategoryColumns,
                    destination = imported,
                )
                continue
            }

            ignoredSheets++
        }

        return ExcelImportResult(
            activities = imported,
            skippedRows = skippedRows,
            ignoredSheets = ignoredSheets,
        )
    }

    private fun importCurrentDaySheet(
        rows: List<List<com.gyanoba.kexcel.sheet.Data?>>,
        date: LocalDate,
        startColumn: Int,
        endColumn: Int,
        descriptionColumn: Int,
        categoryColumn: Int,
        categories: List<ActivityCategory>,
        destination: MutableList<ExcelImportedActivity>,
    ): Int {
        var skippedRows = 0

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

            val category = categoryFromText(categoryText, categories)
            if (description.isBlank() || category == null || startTime == null || endTime == null) {
                skippedRows++
                continue
            }

            destination += importedActivity(
                description = description,
                category = category,
                date = date,
                startTime = startTime,
                endTime = endTime,
            )
        }

        return skippedRows
    }

    private fun importLegacyDaySheet(
        rows: List<List<com.gyanoba.kexcel.sheet.Data?>>,
        date: LocalDate,
        timeColumn: Int,
        descriptionColumn: Int,
        categoryColumns: List<Pair<Int, ActivityCategory>>,
        destination: MutableList<ExcelImportedActivity>,
    ): Int {
        var skippedRows = 0

        for (row in rows.drop(1)) {
            val timeValue = row.getOrNull(timeColumn)?.value
            val description = cellText(row.getOrNull(descriptionColumn)?.value).trim()
            val timeText = cellText(timeValue).trim()

            val markedCategories = categoryColumns.mapNotNull { (column, category) ->
                val marker = cellText(row.getOrNull(column)?.value).trim()
                category.takeIf { marker.isNotBlank() }
            }

            val hasAnyInput = description.isNotBlank() || timeText.isNotBlank() || markedCategories.isNotEmpty()
            if (!hasAnyInput) continue

            val timeRange = parseLegacyTimeRange(timeValue)
            val category = markedCategories.singleOrNull()
            if (description.isBlank() || timeRange == null || category == null) {
                skippedRows++
                continue
            }

            destination += importedActivity(
                description = description,
                category = category,
                date = date,
                startTime = timeRange.first,
                endTime = timeRange.second,
            )
        }

        return skippedRows
    }

    private fun importedActivity(
        description: String,
        category: ActivityCategory,
        date: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
    ): ExcelImportedActivity {
        val endDate = if (endTime < startTime) {
            LocalDate.fromEpochDays(date.toEpochDays() + 1)
        } else {
            date
        }
        return ExcelImportedActivity(
            description = description,
            category = category,
            startDate = date,
            startTime = startTime,
            endDate = endDate,
            endTime = endTime,
        )
    }

    private fun parseLegacyTimeRange(value: CellValue?): Pair<LocalTime, LocalTime>? {
        val text = cellText(value).trim()
        val match = Regex(
            """^[±~]?\s*(\d{1,2}):(\d{2})\s*[–—-]\s*(\d{1,2}):(\d{2})$"""
        ).matchEntire(text) ?: return null

        val start = safeTime(
            hour = match.groupValues[1].toInt(),
            minute = match.groupValues[2].toInt(),
            second = 0,
        ) ?: return null
        val end = safeTime(
            hour = match.groupValues[3].toInt(),
            minute = match.groupValues[4].toInt(),
            second = 0,
        ) ?: return null

        return start to end
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
                val pointsPer30Minutes = item.payload.category.pointsPer30Minutes
                sheet.updateCell(
                    CellIndex.indexByColumnRow(5, rowIndex),
                    FormulaCellValue(
                        "=IF(OR(D$excelRow=\"\",D$excelRow<=0),\"\",D$excelRow/30*$pointsPer30Minutes)"
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

    private fun writeSettings(
        sheet: com.gyanoba.kexcel.sheet.Sheet,
        categories: List<ActivityCategory>,
    ) {
        val headerStyle = CellStyle(bold = true)
        sheet.updateCell(CellIndex.indexByString("A1"), TextCellValue("Categorie"), headerStyle)
        sheet.updateCell(CellIndex.indexByString("B1"), TextCellValue("Punten per 30 min"), headerStyle)

        categories.forEachIndexed { index, category ->
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

    private fun categoryFromText(
        value: String,
        categories: List<ActivityCategory>,
    ): ActivityCategory? {
        val normalized = value.trim().lowercase()
        return categories.firstOrNull {
            it.label.lowercase() == normalized || it.id.lowercase() == normalized
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
