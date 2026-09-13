// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

package net.dikkenberg.activiteitenweger.excel

import com.gyanoba.kexcel.Excel
import com.gyanoba.kexcel.sheet.DoubleCellValue
import com.gyanoba.kexcel.sheet.TextCellValue
import com.gyanoba.kexcel.sheet.TimeCellValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import net.dikkenberg.activiteitenweger.model.ActivityCategory
import net.dikkenberg.activiteitenweger.model.ActivityItem
import net.dikkenberg.activiteitenweger.model.ActivityRecordPayload

class ExcelTransferTest {
    @Test
    fun importsLegacyWorkbookAndHandlesCrossMidnight() {
        val excel = Excel.createExcel()
        excel.rename("Sheet1", "Vrijdag 28 augustus")
        val sheet = excel["Vrijdag 28 augustus"]

        sheet.appendRow(
            listOf(
                TextCellValue("Starttijd"),
                TextCellValue("Eindtijd"),
                TextCellValue("Activiteit"),
                TextCellValue("Minuten"),
                TextCellValue("Categorie"),
                TextCellValue("Punten per activiteit"),
                TextCellValue("Punten totaal"),
            )
        )
        sheet.appendRow(
            listOf(
                TimeCellValue(23, 35),
                TimeCellValue(1, 30),
                TextCellValue("Slapen"),
                null,
                TextCellValue("Ontspanning"),
                null,
                null,
            )
        )
        sheet.appendRow(
            listOf(
                TimeCellValue(8, 0),
                TimeCellValue(8, 10),
                TextCellValue("Geen categorie"),
                null,
                null,
                null,
                null,
            )
        )

        val result = ExcelTransfer.importWorkbook(
            bytes = requireNotNull(excel.encode()),
            fallbackYear = 2026,
        )

        assertEquals(1, result.activities.size)
        assertEquals(1, result.skippedRows)
        assertEquals(0, result.ignoredSheets)

        val imported = result.activities.single()
        assertEquals("Slapen", imported.description)
        assertEquals(ActivityCategory.RELAXATION, imported.category)
        assertEquals(LocalDate(2026, 8, 28), imported.startDate)
        assertEquals(LocalTime(23, 35), imported.startTime)
        assertEquals(LocalDate(2026, 8, 29), imported.endDate)
        assertEquals(LocalTime(1, 30), imported.endTime)
    }


    @Test
    fun importsOriginalLegacyWorkbookLayout() {
        val excel = Excel.createExcel()
        excel.rename("Sheet1", "Vrijdag 28 augustus")
        val sheet = excel["Vrijdag 28 augustus"]

        sheet.appendRow(
            listOf(
                TextCellValue("Tijdstip"),
                TextCellValue("Activiteit"),
                TextCellValue("Duur"),
                TextCellValue("Ontspanning"),
                TextCellValue("Licht"),
                TextCellValue("Gemiddeld"),
                TextCellValue("Zwaar"),
                TextCellValue("Punten per act"),
                TextCellValue("Punten totaal"),
            )
        )
        sheet.appendRow(
            listOf(
                TextCellValue("08:00–08:20"),
                TextCellValue("Zelf wassen wat kan"),
                TextCellValue("20 min"),
                null,
                null,
                null,
                TextCellValue("x"),
                DoubleCellValue(1.5),
                DoubleCellValue(1.5),
            )
        )
        sheet.appendRow(
            listOf(
                TextCellValue("±12:00–12:20"),
                TextCellValue("Vernevelen tijdens computeren"),
                TextCellValue("20 min"),
                null,
                null,
                TextCellValue("x"),
                null,
                DoubleCellValue(1.0),
                DoubleCellValue(2.5),
            )
        )
        sheet.appendRow(
            listOf(
                TextCellValue("vanaf 22:20"),
                TextCellValue("Slapen"),
                TextCellValue("—"),
                null,
                null,
                null,
                null,
                null,
                null,
            )
        )

        val result = ExcelTransfer.importWorkbook(
            bytes = requireNotNull(excel.encode()),
            fallbackYear = 2026,
        )

        assertEquals(2, result.activities.size)
        assertEquals(1, result.skippedRows)
        assertEquals(0, result.ignoredSheets)

        assertEquals("Zelf wassen wat kan", result.activities[0].description)
        assertEquals(ActivityCategory.HEAVY, result.activities[0].category)
        assertEquals(LocalTime(8, 0), result.activities[0].startTime)
        assertEquals(LocalTime(8, 20), result.activities[0].endTime)

        assertEquals("Vernevelen tijdens computeren", result.activities[1].description)
        assertEquals(ActivityCategory.MEDIUM, result.activities[1].category)
        assertEquals(LocalTime(12, 0), result.activities[1].startTime)
        assertEquals(LocalTime(12, 20), result.activities[1].endTime)
    }

    @Test
    fun exportedWorkbookCanBeOpenedAgain() {
        val bytes = ExcelTransfer.exportWorkbook(
            listOf(
                ActivityItem(
                    recordId = "test-record",
                    revision = 1,
                    payload = ActivityRecordPayload(
                        startedAt = "2026-08-28T10:00:00Z",
                        endedAt = "2026-08-28T10:30:00Z",
                        description = "Testactiviteit",
                        category = ActivityCategory.LIGHT,
                    ),
                )
            )
        )

        val excel = Excel.decodeBytes(bytes)
        assertTrue("Instellingen" in excel.getSheets().keys)
        assertEquals(2, excel.getSheets().size)

        val daySheet = excel.getSheets()
            .filterKeys { it != "Instellingen" }
            .values
            .single()

        assertEquals("Starttijd", daySheet.rows[0][0]?.value.toString())
        assertEquals("Testactiviteit", daySheet.rows[1][2]?.value.toString())
        assertEquals("Licht", daySheet.rows[1][4]?.value.toString())
    }
}
