package ru.ikea.cellmapper.logic

import java.util.regex.Pattern

object BarcodeLogic {
    private val SCAN_PATTERN = Pattern.compile("=(\\d{8,})=")
    private val DIGITS_PATTERN = Pattern.compile("(\\d{8,})")

    fun barcodeToArticle(rawDigits: String): String {
        val core = rawDigits.take(8)
        require(core.length >= 8 && core.all { it.isDigit() }) {
            "Недостаточно цифр для артикула (нужно минимум 8)."
        }
        return "${core.substring(0, 3)}.${core.substring(3, 6)}.${core.substring(6, 8)}"
    }

    /** Извлечь цифры ШК из буфера сканера (=...= или любые 8+ цифр). */
    fun extractDigits(input: String): String? {
        val trimmed = input.trim()
        val scanMatch = SCAN_PATTERN.matcher(trimmed)
        if (scanMatch.find()) return scanMatch.group(1)

        val digitsMatch = DIGITS_PATTERN.matcher(trimmed)
        if (digitsMatch.find()) return digitsMatch.group(1)
        return null
    }

    /** Сканер часто шлёт Enter/Tab в конце — считаем штрихкод завершённым. */
    fun isTerminator(char: Char): Boolean = char == '\n' || char == '\r' || char == '\t'

    fun cleanBuffer(buffer: String): String = buffer.replace("\r", "").replace("\n", "").replace("\t", "")
}
