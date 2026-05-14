package ru.ikea.cellmapper.export

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import ru.ikea.cellmapper.data.ItemEntity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

object XlsxStorage {
    private val HEADERS = listOf("cell", "barcode", "article", "qty")

    fun read(context: Context, uri: Uri): List<ItemEntity> {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw).use { input ->
                WorkbookFactory.create(input).use { workbook ->
                    val sheet = workbook.getSheetAt(0) ?: return emptyList()
                    if (sheet.physicalNumberOfRows <= 1) return emptyList()

                    val headerRow = sheet.getRow(0) ?: return emptyList()
                    val columns = HEADERS.associateWith { name ->
                        (0 until headerRow.lastCellNum).firstOrNull { idx ->
                            headerRow.getCell(idx)?.stringCellValue?.equals(name, ignoreCase = true) == true
                        }
                    }

                    val result = mutableListOf<ItemEntity>()
                    for (rowIndex in 1..sheet.lastRowNum) {
                        val row = sheet.getRow(rowIndex) ?: continue
                        val barcode = cellAsString(row, columns["barcode"]) ?: continue
                        if (barcode.isBlank()) continue

                        val article = cellAsString(row, columns["article"]) ?: ""
                        val cell = cellAsInt(row, columns["cell"]) ?: 0
                        val qty = cellAsInt(row, columns["qty"]) ?: 0
                        result += ItemEntity(
                            barcode = barcode.filter { it.isDigit() },
                            article = article,
                            cell = cell,
                            qty = qty
                        )
                    }
                    return result
                }
            }
        }
        return emptyList()
    }

    fun write(context: Context, uri: Uri, items: List<ItemEntity>) {
        val workbook = XSSFWorkbook()
        try {
            val sheet = workbook.createSheet("items")
            val header = sheet.createRow(0)
            HEADERS.forEachIndexed { index, title ->
                header.createCell(index).setCellValue(title)
            }

            items.sortedWith(compareBy({ it.cell }, { it.barcode })).forEachIndexed { index, item ->
                val row = sheet.createRow(index + 1)
                row.createCell(0).setCellValue(item.cell.toDouble())
                row.createCell(1).setCellValue(item.barcode)
                row.createCell(2).setCellValue(item.article)
                row.createCell(3).setCellValue(item.qty.toDouble())
            }

            context.contentResolver.openOutputStream(uri, "rwt")?.use { raw ->
                BufferedOutputStream(raw).use { output ->
                    workbook.write(output)
                }
            } ?: error("Не удалось открыть файл для записи")
        } finally {
            workbook.close()
        }
    }

    private fun cellAsString(row: org.apache.poi.ss.usermodel.Row, index: Int?): String? {
        if (index == null) return null
        val cell = row.getCell(index) ?: return null
        return when (cell.cellType) {
            org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
            org.apache.poi.ss.usermodel.CellType.NUMERIC -> cell.numericCellValue.toLong().toString()
            org.apache.poi.ss.usermodel.CellType.FORMULA -> cell.stringCellValue
            else -> null
        }
    }

    private fun cellAsInt(row: org.apache.poi.ss.usermodel.Row, index: Int?): Int? {
        if (index == null) return null
        val cell = row.getCell(index) ?: return null
        return when (cell.cellType) {
            org.apache.poi.ss.usermodel.CellType.NUMERIC -> cell.numericCellValue.toInt()
            org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue.toIntOrNull()
            else -> null
        }
    }
}
