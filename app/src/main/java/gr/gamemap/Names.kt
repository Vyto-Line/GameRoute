package gr.gamemap

/**
 * Street names for an English-speaking driver: Greek names in Latin letters, the way Greek road
 * signs write them (ELOT 743: "Παναγή Τσαλδάρη" → "Panagi Tsaldari", "Αιόλου" → "Aiolou").
 * In Greek, or for names already in Latin letters, the name stays as it is.
 */
object Names {
    private val pairs = listOf(
        "ου" to "ou", "αυ" to "av", "ευ" to "ev", "ηυ" to "iv", "αύ" to "av", "εύ" to "ev", "ού" to "ou",
        "γγ" to "ng", "γξ" to "nx", "γχ" to "nch", "θ" to "th", "χ" to "ch", "ψ" to "ps",
    )
    private val single = mapOf(
        'α' to "a", 'ά' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'έ' to "e", 'ζ' to "z", 'η' to "i", 'ή' to "i",
        'ι' to "i", 'ί' to "i", 'ϊ' to "i", 'ΐ' to "i", 'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'ό' to "o",
        'π' to "p", 'ρ' to "r", 'σ' to "s", 'ς' to "s", 'τ' to "t", 'υ' to "y", 'ύ' to "y", 'ϋ' to "y", 'ΰ' to "y", 'φ' to "f", 'ω' to "o", 'ώ' to "o",
    )

    fun hasGreek(s: String) = s.any { it in 'Ͱ'..'Ͽ' || it in 'ἀ'..'῿' }

    /** Greek letters in Latin ones, keeping capitals ("ΣΤΑΔΙΟΥ" → "STADIOU", "Αιόλου" → "Aiolou"). */
    fun latin(s: String): String {
        if (!hasGreek(s)) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val lower = ch.lowercaseChar()
            val upper = ch != lower
            var piece: String? = null; var used = 1
            if (i + 1 < s.length) {
                val two = "$lower${s[i + 1].lowercaseChar()}"
                pairs.firstOrNull { it.first == two }?.let { piece = it.second; used = 2 }
            }
            if (piece == null) pairs.firstOrNull { it.first == lower.toString() }?.let { piece = it.second }
            if (piece == null) piece = single[lower]
            val p = piece ?: ch.toString()
            // capitals: the whole piece in capitals when the next letter is one too (ΣΤΑΔΙΟΥ), else just the first
            val nextUpper = i + used < s.length && s[i + used].isUpperCase()
            out.append(if (!upper || piece == null) p else if (nextUpper || (i > 0 && s[i - 1].isUpperCase())) p.uppercase() else p.replaceFirstChar { it.uppercase() })
            i += used
        }
        return out.toString()
    }

    /** A street name as this driver reads it: in Latin letters when the app is in English. */
    fun shown(name: String): String = if (L.greek || name.isBlank()) name else latin(name)
}
