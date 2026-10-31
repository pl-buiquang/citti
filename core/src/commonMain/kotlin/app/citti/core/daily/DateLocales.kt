package app.citti.core.daily

/**
 * The month and weekday names and the week rule of one Date locale, copied from Moment.js 2.29.4 (MIT, see
 * `licenses/moment/LICENSE`), the version Obsidian bundles.
 *
 * [weekdays] and [weekdaysShort] are Sunday-first, like Moment's `day()` (0 = Sunday). [dow] is the first day of the
 * week (0 = Sunday, 1 = Monday) and [doy] is `dow + 7 -` the day of January that is always in week 1; together they
 * are Moment's week rule for `ww` and `w`.
 */
data class DateLocale(
    val code: String,
    val months: List<String>,
    val monthsShort: List<String>,
    /** Moment's format-context short months, used when the pattern contains `-MMM-` (no dot in es and nl). */
    val monthsShortDashed: List<String> = monthsShort,
    val weekdays: List<String>,
    val weekdaysShort: List<String>,
    val dow: Int,
    val doy: Int,
) {
    init {
        require(months.size == 12 && monthsShort.size == 12 && monthsShortDashed.size == 12)
        require(weekdays.size == 7 && weekdaysShort.size == 7)
    }

    /** The short month names for [pattern], with Moment's `/-MMM-/.test(format)` rule on the whole pattern. */
    fun monthsShortFor(pattern: String): List<String> = if (DASHED_MMM in pattern) monthsShortDashed else monthsShort

    private companion object {
        const val DASHED_MMM = "-MMM-"
    }
}

/** The Date locales citti knows: en, fr, de, es, it, pt and nl. */
object DateLocales {
    val en =
        DateLocale(
            code = "en",
            months =
                listOf(
                    "January",
                    "February",
                    "March",
                    "April",
                    "May",
                    "June",
                    "July",
                    "August",
                    "September",
                    "October",
                    "November",
                    "December",
                ),
            monthsShort = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
            weekdays = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"),
            weekdaysShort = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
            dow = 0,
            doy = 6,
        )

    val fr =
        DateLocale(
            code = "fr",
            months =
                listOf(
                    "janvier",
                    "février",
                    "mars",
                    "avril",
                    "mai",
                    "juin",
                    "juillet",
                    "août",
                    "septembre",
                    "octobre",
                    "novembre",
                    "décembre",
                ),
            monthsShort =
                listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."),
            weekdays = listOf("dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi"),
            weekdaysShort = listOf("dim.", "lun.", "mar.", "mer.", "jeu.", "ven.", "sam."),
            dow = 1,
            doy = 4,
        )

    val de =
        DateLocale(
            code = "de",
            months =
                listOf(
                    "Januar",
                    "Februar",
                    "März",
                    "April",
                    "Mai",
                    "Juni",
                    "Juli",
                    "August",
                    "September",
                    "Oktober",
                    "November",
                    "Dezember",
                ),
            monthsShort =
                listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sep.", "Okt.", "Nov.", "Dez."),
            weekdays = listOf("Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"),
            weekdaysShort = listOf("So.", "Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa."),
            dow = 1,
            doy = 4,
        )

    val es =
        DateLocale(
            code = "es",
            months =
                listOf(
                    "enero",
                    "febrero",
                    "marzo",
                    "abril",
                    "mayo",
                    "junio",
                    "julio",
                    "agosto",
                    "septiembre",
                    "octubre",
                    "noviembre",
                    "diciembre",
                ),
            monthsShort =
                listOf("ene.", "feb.", "mar.", "abr.", "may.", "jun.", "jul.", "ago.", "sep.", "oct.", "nov.", "dic."),
            monthsShortDashed =
                listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"),
            weekdays = listOf("domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"),
            weekdaysShort = listOf("dom.", "lun.", "mar.", "mié.", "jue.", "vie.", "sáb."),
            dow = 1,
            doy = 4,
        )

    val it =
        DateLocale(
            code = "it",
            months =
                listOf(
                    "gennaio",
                    "febbraio",
                    "marzo",
                    "aprile",
                    "maggio",
                    "giugno",
                    "luglio",
                    "agosto",
                    "settembre",
                    "ottobre",
                    "novembre",
                    "dicembre",
                ),
            monthsShort = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic"),
            weekdays = listOf("domenica", "lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato"),
            weekdaysShort = listOf("dom", "lun", "mar", "mer", "gio", "ven", "sab"),
            dow = 1,
            doy = 4,
        )

    val pt =
        DateLocale(
            code = "pt",
            months =
                listOf(
                    "janeiro",
                    "fevereiro",
                    "março",
                    "abril",
                    "maio",
                    "junho",
                    "julho",
                    "agosto",
                    "setembro",
                    "outubro",
                    "novembro",
                    "dezembro",
                ),
            monthsShort = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"),
            weekdays =
                listOf("Domingo", "Segunda-feira", "Terça-feira", "Quarta-feira", "Quinta-feira", "Sexta-feira", "Sábado"),
            weekdaysShort = listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb"),
            dow = 1,
            doy = 4,
        )

    val nl =
        DateLocale(
            code = "nl",
            months =
                listOf(
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
                ),
            monthsShort =
                listOf("jan.", "feb.", "mrt.", "apr.", "mei", "jun.", "jul.", "aug.", "sep.", "okt.", "nov.", "dec."),
            monthsShortDashed =
                listOf("jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec"),
            weekdays = listOf("zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"),
            weekdaysShort = listOf("zo.", "ma.", "di.", "wo.", "do.", "vr.", "za."),
            dow = 1,
            doy = 4,
        )

    val all: List<DateLocale> = listOf(en, fr, de, es, it, pt, nl)

    /** The locale for [code] (`en`…`nl`), or en for anything else. */
    fun forCode(code: String?): DateLocale = all.firstOrNull { locale -> locale.code == code } ?: en
}
