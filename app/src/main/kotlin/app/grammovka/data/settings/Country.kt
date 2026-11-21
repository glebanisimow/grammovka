package app.grammovka.data.settings

/**
 * Where the user buys food (2026-11-21: the app also runs in Belarus). It steers product search: the prompts, the
 * Open Food Facts country filter, the web search location and the label vocabulary.
 */
enum class Country(
    val title: String,
    val nameEn: String,
    /** ISO 3166-1 alpha-2, also the OFF `country` argument in lower case. */
    val iso: String,
    val timezone: String,
    /** Open Food Facts `countries_tags` value. */
    val offTag: String,
    val packagedNote: String,
    val shops: String,
    val labelHint: String,
) {
    PL(
        "Польша", "Poland", "PL", "Europe/Warsaw", "en:poland",
        packagedNote = "packaged products are mostly Polish",
        shops = "Polish shops",
        labelHint = "Labels are often Polish («Wartość odżywcza», «w 100 g», «Wartość energetyczna», «Tłuszcz», «Węglowodany», «Błonnik», «Białko»).",
    ),
    BY(
        "Беларусь", "Belarus", "BY", "Europe/Minsk", "en:belarus",
        packagedNote = "packaged products are mostly Belarusian or Russian",
        shops = "Belarusian and Russian shops",
        labelHint = "Labels are often Russian or Belarusian («Пищевая ценность», «в 100 г», «Энергетическая ценность», «Белки», «Жиры», «Углеводы», «Пищевые волокна»; «Харчовая каштоўнасць», «Бялкі», «Тлушчы», «Вугляводы»).",
    ),
}
