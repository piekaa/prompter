package pl.piekoszek.prompter.core

/**
 * Token normalization shared by alignment scoring and grammar building.
 * Pure JVM — no Android dependencies.
 */
object Normalizer {

    /** Anything that is not a letter or digit (punctuation, quotes, dashes). */
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    private val WHITESPACE = Regex("\\s+")

    /**
     * Splits text into word tokens: lowercase, punctuation removed,
     * runs of whitespace collapsed, empty tokens dropped.
     */
    fun words(text: String): List<String> =
        text.lowercase()
            .replace(NON_WORD, " ")
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }

    /**
     * Diacritic folding for tolerant comparison (PROJEKT 5.1):
     * ł≈l, ą≈a, ć≈c … applied to both sides of the similarity check.
     */
    private val FOLD: Map<Char, Char> = mapOf(
        // Polish
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
        // Other common Latin accents (Vosk output quirks)
        'à' to 'a', 'á' to 'a', 'è' to 'e', 'é' to 'e', 'ì' to 'i',
        'í' to 'i', 'ò' to 'o', 'ú' to 'u', 'ù' to 'u', 'ý' to 'y', 'ñ' to 'n',
    )

    fun fold(s: String): String = buildString {
        for (c in s) append(FOLD[c] ?: c)
    }

    /** Normalizes a single token for comparison: lowercase, punctuation-free. */
    fun foldToken(s: String): String = fold(s.lowercase().replace(NON_WORD, ""))
}
