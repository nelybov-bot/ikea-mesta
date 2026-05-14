package ru.ikea.cellmapper.logic

import java.util.regex.Pattern

object BarcodeLogic {
    private val SCAN_PATTERN = Pattern.compile("=(\\d{8,})=")

    /** Русские буквы → латиница (та же клавиша), если сканер печатает «в русской раскладке». */
    private val RU_LAYOUT_TO_LATIN = mapOf(
        'й' to 'q', 'ц' to 'w', 'у' to 'e', 'к' to 'r', 'е' to 't', 'н' to 'y', 'г' to 'u',
        'ш' to 'i', 'щ' to 'o', 'з' to 'p', 'х' to '[', 'ъ' to ']', 'ф' to 'a', 'ы' to 's',
        'в' to 'd', 'а' to 'f', 'п' to 'g', 'р' to 'h', 'о' to 'j', 'л' to 'k', 'д' to 'l',
        'ж' to ';', 'э' to '\'', 'я' to 'z', 'ч' to 'x', 'с' to 'c', 'м' to 'v', 'и' to 'b',
        'т' to 'n', 'ь' to 'm', 'б' to ',', 'ю' to '.',
        'Й' to 'Q', 'Ц' to 'W', 'У' to 'E', 'К' to 'R', 'Е' to 'T', 'Н' to 'Y', 'Г' to 'U',
        'Ш' to 'I', 'Щ' to 'O', 'З' to 'P', 'Х' to '{', 'Ъ' to '}', 'Ф' to 'A', 'Ы' to 'S',
        'В' to 'D', 'А' to 'F', 'П' to 'G', 'Р' to 'H', 'О' to 'J', 'Л' to 'K', 'Д' to 'L',
        'Ж' to ':', 'Э' to '"', 'Я' to 'Z', 'Ч' to 'X', 'С' to 'C', 'М' to 'V', 'И' to 'B',
        'Т' to 'N', 'Ь' to 'M', 'Б' to '<', 'Ю' to '>'
    )

    fun barcodeToArticle(rawDigits: String): String {
        val core = rawDigits.take(8)
        require(core.length >= 8 && core.all { it.isDigit() }) {
            "Недостаточно цифр для артикула (нужно минимум 8)."
        }
        return "${core.substring(0, 3)}.${core.substring(3, 6)}.${core.substring(6, 8)}"
    }

    fun extractDigits(input: String): String? {
        val variants = listOf(
            input.trim(),
            normalizeKeyboardLayout(input.trim()),
            input.filter { it.isDigit() }
        )
        for (raw in variants) {
            val scanMatch = SCAN_PATTERN.matcher(raw)
            if (scanMatch.find()) return scanMatch.group(1)

            val digitsOnly = raw.filter { it.isDigit() }
            if (digitsOnly.length >= 8) return digitsOnly
        }
        return null
    }

    /** Если в буфере только буквы (кракозябры) — попробовать раскладку. */
    fun normalizeKeyboardLayout(input: String): String =
        input.map { ch -> RU_LAYOUT_TO_LATIN[ch] ?: ch }.joinToString("")

    fun cleanBuffer(buffer: String): String =
        buffer.replace("\r", "").replace("\n", "").replace("\t", "")

    fun isTerminatorChar(char: Char): Boolean =
        char == '\n' || char == '\r' || char == '\t'
}
