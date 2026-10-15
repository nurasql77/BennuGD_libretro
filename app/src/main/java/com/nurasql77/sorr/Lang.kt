package com.nurasql77.sorr

/** Язык интерфейса: по умолчанию English, можно переключить на русский и казахский. */
object Lang {
    @Volatile
    var code: String = "en"

    val codes = listOf("en", "ru", "kk")

    fun name(c: String): String = when (c) {
        "ru" -> "Русский"
        "kk" -> "Қазақша"
        else -> "English"
    }

    fun next(c: String): String = codes[(codes.indexOf(c) + 1) % codes.size]
}

/** Выбирает строку по текущему языку: tr(английский, русский, казахский). */
fun tr(en: String, ru: String, kk: String): String = when (Lang.code) {
    "ru" -> ru
    "kk" -> kk
    else -> en
}
