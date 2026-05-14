package ru.ikea.cellmapper.export

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import ru.ikea.cellmapper.data.ItemEntity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Минимальный читатель/писатель XLSX без Apache POI (POI на Android не собирается).
 */
object XlsxStorage {
    private val HEADERS = listOf("cell", "barcode", "article", "qty")

    fun read(context: Context, uri: Uri): List<ItemEntity> {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw).use { input ->
                val entries = readZipEntries(input)
                val sheetXml = entries["xl/worksheets/sheet1.xml"] ?: return emptyList()
                return parseSheet(String(sheetXml, Charsets.UTF_8))
            }
        }
        return emptyList()
    }

    fun write(context: Context, uri: Uri, items: List<ItemEntity>) {
        val rows = buildRows(items)
        val sheetXml = buildSheetXml(rows)
        val workbookXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="items" sheetId="1" r:id="rId1"/>
              </sheets>
            </workbook>
        """.trimIndent()
        val workbookRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent()
        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>
        """.trimIndent()
        val rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
        """.trimIndent()

        context.contentResolver.openOutputStream(uri, "rwt")?.use { raw ->
            BufferedOutputStream(raw).use { output ->
                ZipOutputStream(output).use { zip ->
                    writeEntry(zip, "[Content_Types].xml", contentTypes)
                    writeEntry(zip, "_rels/.rels", rootRels)
                    writeEntry(zip, "xl/workbook.xml", workbookXml)
                    writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRels)
                    writeEntry(zip, "xl/worksheets/sheet1.xml", sheetXml)
                }
            }
        } ?: error("Не удалось открыть файл для записи")
    }

    private fun buildRows(items: List<ItemEntity>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        rows += HEADERS
        items.sortedWith(compareBy({ it.cell }, { it.barcode })).forEach { item ->
            rows += listOf(item.cell.toString(), item.barcode, item.article, item.qty.toString())
        }
        return rows
    }

    private fun buildSheetXml(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { rowIndex, row ->
            val r = rowIndex + 1
            sb.append("""<row r="$r">""")
            row.forEachIndexed { colIndex, value ->
                val cellRef = columnName(colIndex) + r
                if (value.toIntOrNull() != null && colIndex != 1) {
                    sb.append("""<c r="$cellRef"><v>$value</v></c>""")
                } else {
                    sb.append("""<c r="$cellRef" t="inlineStr"><is><t>${escapeXml(value)}</t></is></c>""")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun parseSheet(xml: String): List<ItemEntity> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())

        val table = mutableListOf<List<String>>()
        var event = parser.eventType
        var currentRow = mutableListOf<String>()
        var inValue = false
        var inInlineText = false
        var textBuffer = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = mutableListOf()
                    "v", "t" -> {
                        inValue = parser.name == "v"
                        inInlineText = parser.name == "t"
                        textBuffer = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValue || inInlineText) textBuffer.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> {
                        currentRow += textBuffer.toString()
                        inValue = false
                        inInlineText = false
                    }
                    "row" -> if (currentRow.isNotEmpty()) table += currentRow.toList()
                }
            }
            event = parser.next()
        }

        if (table.size <= 1) return emptyList()
        return table.drop(1).mapNotNull { cols ->
            if (cols.size < 4) return@mapNotNull null
            val barcode = cols[1].filter { it.isDigit() }
            if (barcode.isBlank()) return@mapNotNull null
            ItemEntity(
                barcode = barcode,
                article = cols[2],
                cell = cols[0].toIntOrNull() ?: 0,
                qty = cols[3].toIntOrNull() ?: 0
            )
        }
    }

    private fun readZipEntries(input: ZipInputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        var entry = input.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                map[entry.name] = input.readBytes()
            }
            input.closeEntry()
            entry = input.nextEntry
        }
        return map
    }

    private fun readZipEntries(input: BufferedInputStream): Map<String, ByteArray> =
        readZipEntries(ZipInputStream(input))

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun columnName(index: Int): String {
        var i = index
        var name = ""
        while (i >= 0) {
            name = ('A' + (i % 26)) + name
            i = i / 26 - 1
        }
        return name
    }

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
