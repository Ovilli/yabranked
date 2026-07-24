package dev.yabranked.client

/**
 * Country codes with display names, backing the flag picker. The code list is
 * every 2-letter flag shipped under textures/flags; [names] supplies a friendly
 * label for the common ones (search matches code or name, so unnamed codes are
 * still findable by their two letters).
 */
object CountryData {

    /** All selectable ISO 3166-1 alpha-2 codes we ship a flag for, A→Z. */
    val codes: List<String> = (
        "ad ae af ag ai al am ao ar as at au aw ax az ba bb bd be bf bg bh bi bj " +
        "bl bm bn bo bq br bs bt bw by bz ca cc cd cf cg ch ci ck cl cm cn co cr " +
        "cu cv cw cx cy cz de dj dk dm do dz ec ee eg eh er es et fi fj fk fm fo " +
        "fr ga gb gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk hn hr ht " +
        "hu id ie il im in io iq ir is it je jm jo jp ke kg kh ki km kn kp kr kw " +
        "ky kz la lb lc li lk lr ls lt lu lv ly ma mc md me mf mg mh mk ml mm mn " +
        "mo mp mq mr ms mt mu mv mw mx my mz na nc ne nf ng ni nl no np nr nu nz " +
        "om pa pe pf pg ph pk pl pm pn pr ps pt pw py qa re ro rs ru rw sa sb sc " +
        "sd se sg sh si sj sk sl sm sn so sr ss st sv sx sy sz tc td tf tg th tj " +
        "tk tl tm tn to tr tt tv tw tz ua ug us uy uz va vc ve vg vi vn vu wf ws " +
        "xk ye yt za zm zw"
    ).split(" ").filter { it.isNotBlank() }

    /** code → English name; codes absent here fall back to their upper-cased code. */
    private val names: Map<String, String> = mapOf(
        "ad" to "Andorra", "ae" to "United Arab Emirates", "af" to "Afghanistan",
        "ag" to "Antigua & Barbuda", "al" to "Albania", "am" to "Armenia",
        "ao" to "Angola", "ar" to "Argentina", "at" to "Austria", "au" to "Australia",
        "az" to "Azerbaijan", "ba" to "Bosnia & Herzegovina", "bb" to "Barbados",
        "bd" to "Bangladesh", "be" to "Belgium", "bf" to "Burkina Faso",
        "bg" to "Bulgaria", "bh" to "Bahrain", "bi" to "Burundi", "bj" to "Benin",
        "bn" to "Brunei", "bo" to "Bolivia", "br" to "Brazil", "bs" to "Bahamas",
        "bt" to "Bhutan", "bw" to "Botswana", "by" to "Belarus", "bz" to "Belize",
        "ca" to "Canada", "cd" to "DR Congo", "cf" to "Central African Republic",
        "cg" to "Congo", "ch" to "Switzerland", "ci" to "Côte d'Ivoire",
        "cl" to "Chile", "cm" to "Cameroon", "cn" to "China", "co" to "Colombia",
        "cr" to "Costa Rica", "cu" to "Cuba", "cv" to "Cape Verde", "cy" to "Cyprus",
        "cz" to "Czechia", "de" to "Germany", "dj" to "Djibouti", "dk" to "Denmark",
        "dm" to "Dominica", "do" to "Dominican Republic", "dz" to "Algeria",
        "ec" to "Ecuador", "ee" to "Estonia", "eg" to "Egypt", "er" to "Eritrea",
        "es" to "Spain", "et" to "Ethiopia", "fi" to "Finland", "fj" to "Fiji",
        "fm" to "Micronesia", "fo" to "Faroe Islands", "fr" to "France",
        "ga" to "Gabon", "gb" to "United Kingdom", "gd" to "Grenada",
        "ge" to "Georgia", "gh" to "Ghana", "gi" to "Gibraltar", "gl" to "Greenland",
        "gm" to "Gambia", "gn" to "Guinea", "gq" to "Equatorial Guinea",
        "gr" to "Greece", "gt" to "Guatemala", "gw" to "Guinea-Bissau",
        "gy" to "Guyana", "hk" to "Hong Kong", "hn" to "Honduras", "hr" to "Croatia",
        "ht" to "Haiti", "hu" to "Hungary", "id" to "Indonesia", "ie" to "Ireland",
        "il" to "Israel", "im" to "Isle of Man", "in" to "India", "iq" to "Iraq",
        "ir" to "Iran", "is" to "Iceland", "it" to "Italy", "jm" to "Jamaica",
        "jo" to "Jordan", "jp" to "Japan", "ke" to "Kenya", "kg" to "Kyrgyzstan",
        "kh" to "Cambodia", "ki" to "Kiribati", "km" to "Comoros",
        "kn" to "St Kitts & Nevis", "kp" to "North Korea", "kr" to "South Korea",
        "kw" to "Kuwait", "kz" to "Kazakhstan", "la" to "Laos", "lb" to "Lebanon",
        "lc" to "St Lucia", "li" to "Liechtenstein", "lk" to "Sri Lanka",
        "lr" to "Liberia", "ls" to "Lesotho", "lt" to "Lithuania",
        "lu" to "Luxembourg", "lv" to "Latvia", "ly" to "Libya", "ma" to "Morocco",
        "mc" to "Monaco", "md" to "Moldova", "me" to "Montenegro",
        "mg" to "Madagascar", "mh" to "Marshall Islands", "mk" to "North Macedonia",
        "ml" to "Mali", "mm" to "Myanmar", "mn" to "Mongolia", "mo" to "Macau",
        "mr" to "Mauritania", "mt" to "Malta", "mu" to "Mauritius",
        "mv" to "Maldives", "mw" to "Malawi", "mx" to "Mexico", "my" to "Malaysia",
        "mz" to "Mozambique", "na" to "Namibia", "ne" to "Niger", "ng" to "Nigeria",
        "ni" to "Nicaragua", "nl" to "Netherlands", "no" to "Norway", "np" to "Nepal",
        "nr" to "Nauru", "nz" to "New Zealand", "om" to "Oman", "pa" to "Panama",
        "pe" to "Peru", "pg" to "Papua New Guinea", "ph" to "Philippines",
        "pk" to "Pakistan", "pl" to "Poland", "pr" to "Puerto Rico",
        "ps" to "Palestine", "pt" to "Portugal", "pw" to "Palau", "py" to "Paraguay",
        "qa" to "Qatar", "ro" to "Romania", "rs" to "Serbia", "ru" to "Russia",
        "rw" to "Rwanda", "sa" to "Saudi Arabia", "sb" to "Solomon Islands",
        "sc" to "Seychelles", "sd" to "Sudan", "se" to "Sweden", "sg" to "Singapore",
        "si" to "Slovenia", "sk" to "Slovakia", "sl" to "Sierra Leone",
        "sm" to "San Marino", "sn" to "Senegal", "so" to "Somalia", "sr" to "Suriname",
        "ss" to "South Sudan", "st" to "São Tomé & Príncipe", "sv" to "El Salvador",
        "sy" to "Syria", "sz" to "Eswatini", "td" to "Chad", "tg" to "Togo",
        "th" to "Thailand", "tj" to "Tajikistan", "tl" to "Timor-Leste",
        "tm" to "Turkmenistan", "tn" to "Tunisia", "to" to "Tonga", "tr" to "Türkiye",
        "tt" to "Trinidad & Tobago", "tv" to "Tuvalu", "tw" to "Taiwan",
        "tz" to "Tanzania", "ua" to "Ukraine", "ug" to "Uganda",
        "us" to "United States", "uy" to "Uruguay", "uz" to "Uzbekistan",
        "va" to "Vatican City", "vc" to "St Vincent", "ve" to "Venezuela",
        "vn" to "Vietnam", "vu" to "Vanuatu", "ws" to "Samoa", "xk" to "Kosovo",
        "ye" to "Yemen", "za" to "South Africa", "zm" to "Zambia", "zw" to "Zimbabwe",
    )

    fun name(code: String): String = names[code.lowercase()] ?: code.uppercase()

    /** Codes whose name or code contains [query] (case-insensitive), keeping A→Z order. */
    fun search(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return codes
        return codes.filter { it.contains(q) || name(it).lowercase().contains(q) }
    }
}
