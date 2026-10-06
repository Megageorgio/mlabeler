package mlabeler.app.i18n

/** Names of spoken languages in the interface language, by ISO code ("ja", "zh", "yue"…). */
object LanguageNames {
    private val names = mapOf(
        "en" to L("English", "Английский"),
        "ru" to L("Russian", "Русский"),
        "uk" to L("Ukrainian", "Украинский"),
        "be" to L("Belarusian", "Белорусский"),
        "ja" to L("Japanese", "Японский"),
        "zh" to L("Chinese", "Китайский"),
        "yue" to L("Cantonese", "Кантонский"),
        "ko" to L("Korean", "Корейский"),
        "fr" to L("French", "Французский"),
        "de" to L("German", "Немецкий"),
        "es" to L("Spanish", "Испанский"),
        "pt" to L("Portuguese", "Португальский"),
        "it" to L("Italian", "Итальянский"),
        "pl" to L("Polish", "Польский"),
        "cs" to L("Czech", "Чешский"),
        "nl" to L("Dutch", "Нидерландский"),
        "tr" to L("Turkish", "Турецкий"),
        "vi" to L("Vietnamese", "Вьетнамский"),
        "th" to L("Thai", "Тайский"),
        "id" to L("Indonesian", "Индонезийский"),
        "tl" to L("Tagalog", "Тагальский"),
        "ar" to L("Arabic", "Арабский"),
        "fi" to L("Finnish", "Финский"),
        "sv" to L("Swedish", "Шведский"),
        "he" to L("Hebrew", "Иврит"),
        "hi" to L("Hindi", "Хинди"),
        "el" to L("Greek", "Греческий"),
        "hu" to L("Hungarian", "Венгерский"),
        "ro" to L("Romanian", "Румынский"),
        "kk" to L("Kazakh", "Казахский"),
        "*" to L("Any language", "Любой язык"),
    )

    /** The name for [code]; [fallback] (what the toolkit sent) for codes not in the list. */
    fun of(code: String, fallback: String = code): String {
        val base = code.lowercase().replace('-', '_')
        return (names[base] ?: names[base.substringBefore('_')])?.invoke() ?: fallback
    }
}
