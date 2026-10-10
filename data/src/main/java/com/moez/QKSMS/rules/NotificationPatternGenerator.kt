package dev.octoshrimpy.quik.rules

import java.util.regex.Pattern
import kotlin.math.ceil

/**
 * Turns "this part of this SMS" into a regex that finds the same part in later SMS from the sender.
 *
 * The sample is split into typed tokens (dates, times, amounts, masked cards, links, words). Typed
 * tokens are always generalised. Whether a word is fixed text or something that changes (a merchant
 * name, "bugün") is learned by aligning the sample with the sender's other messages, the way log
 * template miners separate constant text from variables. The selected tokens become the capture group
 * and the nearest fixed words around it become the anchors.
 */
object NotificationPatternGenerator {

    enum class Kind { URL, DATE, TIME, CARD, NUMBER, WORD, PUNCT }

    data class Token(val text: String, val kind: Kind, val start: Int, val end: Int) {
        /** Typed tokens compare by kind, so two different dates still align with each other. */
        val key: String = when (kind) {
            Kind.WORD, Kind.PUNCT -> text.lowercase()
            else -> "<$kind>"
        }
    }

    data class Result(val pattern: String, val value: String)

    /** Fixed words to keep on each side of the value. */
    private const val CONTEXT_WORDS = 2
    /** How alike (0..1, by aligned tokens) another message must be to count as the same template. */
    private const val MIN_SIMILARITY = 0.5
    /** Share of similar messages a word must appear in, in the same place, to count as fixed text. */
    private const val MIN_STABILITY = 0.6

    private const val MONTHS = "ocak|şubat|subat|mart|nisan|mayıs|mayis|haziran|temmuz|ağustos|agustos|eylül|eylul|" +
            "ekim|kasım|kasim|aralık|aralik|january|february|march|april|may|june|july|august|september|" +
            "october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"

    private val TOKEN = Pattern.compile(
        "(?<url>https?://\\S+|www\\.\\S+)" +
                "|(?<date>\\d{4}-\\d{1,2}-\\d{1,2}(?!\\d)|\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}(?!\\d)" +
                "|\\d{1,2}\\s+(?:$MONTHS)(?!\\p{L})(?:\\s+\\d{4})?)" +
                "|(?<time>\\d{1,2}:\\d{2}(?::\\d{2})?)" +
                "|(?<card>\\d{0,6}[*xX•]{2,}\\d{2,4})" +
                // Codes like 1Z999AA101 that start with a digit; "450TL" still splits into 450 + TL
                "|(?<code>\\p{N}+\\p{L}+\\p{N}[\\p{L}\\p{N}]*)" +
                "|(?<number>\\d(?:[\\d.,]*\\d)?)" +
                "|(?<word>[\\p{L}\\p{N}]+)" +
                "|(?<punct>[^\\s\\p{L}\\p{N}])",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    private const val GAP = "[^\\n]*?"
    private const val NUMBER_RE = "(?<![\\d.,])\\d(?:[\\d.,]*\\d)?"
    private const val CODE_RE = "[\\p{L}\\p{N}]+(?:[-_/][\\p{L}\\p{N}]+)*"

    private fun kindRegex(kind: Kind): String = when (kind) {
        Kind.URL -> "\\S+"
        Kind.DATE -> "\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}|\\d{1,2}\\s+\\p{L}+(?:\\s+\\d{4})?"
        Kind.TIME -> "\\d{1,2}:\\d{2}(?::\\d{2})?"
        Kind.CARD -> "\\d{0,6}[*xX•]{2,}\\d{2,4}"
        Kind.NUMBER -> NUMBER_RE
        Kind.WORD -> CODE_RE
        Kind.PUNCT -> "[^\\s\\p{L}\\p{N}]"
    }

    fun tokenize(text: String): List<Token> {
        val matcher = TOKEN.matcher(text)
        val tokens = mutableListOf<Token>()
        while (matcher.find()) {
            val kind = when {
                matcher.group("url") != null -> Kind.URL
                matcher.group("date") != null -> Kind.DATE
                matcher.group("time") != null -> Kind.TIME
                matcher.group("card") != null -> Kind.CARD
                matcher.group("number") != null -> Kind.NUMBER
                matcher.group("word") != null || matcher.group("code") != null -> Kind.WORD
                else -> Kind.PUNCT
            }
            tokens += Token(matcher.group(), kind, matcher.start(), matcher.end())
        }
        return tokens
    }

    /**
     * @param sample the message text exactly as displayed, so [selectionStart]/[selectionEnd] index into it
     * @param examples other messages from the same sender, used to learn which words are fixed
     * @return null when the selection has no text in it or no working pattern could be built
     */
    fun generate(sample: String, selectionStart: Int, selectionEnd: Int, examples: List<String>): Result? {
        val tokens = tokenize(sample)
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, sample.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, sample.length)
        if (sample.substring(start, end).isBlank()) return null
        // Snap the selection out to whole tokens, so "12.31" of "12.312,50" still means the amount
        val valueFirst = tokens.indexOfFirst { it.end > start && it.start < end }
        val valueLast = tokens.indexOfLast { it.end > start && it.start < end }
        if (valueFirst < 0) return null

        val template = learnTemplate(tokens, examples.filter { it != sample })
        val value = sample.substring(tokens[valueFirst].start, tokens[valueLast].end)

        // More context helps when an anchor word also appears earlier in the message; less helps
        // when the template only shares a word or two with the sample
        return listOf(CONTEXT_WORDS, CONTEXT_WORDS + 1, CONTEXT_WORDS + 2, 1)
            .asSequence()
            .mapNotNull { contextWords ->
                buildPattern(tokens, template, valueFirst, valueLast, contextWords)
                    .takeIf { pattern -> extract(pattern, sample) == value.trim() }
            }
            .firstOrNull()
            ?.let { pattern -> Result(pattern, value.trim()) }
    }

    /** Applies a generated pattern; null when it does not match. */
    fun extract(pattern: String, body: String): String? {
        val match = runCatching { Regex(pattern).find(body) }.getOrNull() ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** What the sender's other messages say about each sample token. */
    private class Template(
        /** Word is fixed text of the template (or assumed so when there is nothing to compare with). */
        val stable: BooleanArray,
        /** Some similar message had extra tokens between sample tokens i and i+1. */
        val gapAfter: BooleanArray
    )

    private fun learnTemplate(tokens: List<Token>, examples: List<String>): Template {
        val keys = tokens.map { it.key }
        val stableCount = IntArray(tokens.size)
        val gapAfter = BooleanArray(tokens.size)
        var similarCount = 0

        examples.forEach { example ->
            val otherKeys = tokenize(example).map { it.key }
            if (otherKeys.isEmpty()) return@forEach
            val matches = align(keys, otherKeys)
            val aligned = matches.count { it >= 0 }
            if (2.0 * aligned / (keys.size + otherKeys.size) < MIN_SIMILARITY) return@forEach

            similarCount++
            matches.forEachIndexed { i, j ->
                if (j >= 0) stableCount[i]++
                val next = matches.getOrNull(i + 1) ?: return@forEachIndexed
                if (j >= 0 && next >= 0 && next - j > 1) gapAfter[i] = true
            }
        }

        val stable = BooleanArray(tokens.size) { i ->
            when {
                tokens[i].kind != Kind.WORD && tokens[i].kind != Kind.PUNCT -> false
                similarCount == 0 -> !looksLikeName(tokens, i)
                else -> stableCount[i] >= ceil(MIN_STABILITY * similarCount)
            }
        }
        return Template(stable, gapAfter)
    }

    /**
     * With a single message there is nothing to compare against, so guess: an ALL-CAPS word in a
     * mixed-case message is usually a merchant or person name rather than template text.
     */
    private fun looksLikeName(tokens: List<Token>, index: Int): Boolean {
        val text = tokens[index].text
        if (tokens[index].kind != Kind.WORD || text.length < 4 || text.any { it.isDigit() }) return false
        if (text != text.uppercase()) return false
        return tokens.any { it.kind == Kind.WORD && it.text.any(Char::isLowerCase) }
    }

    /** Longest common subsequence alignment; result[i] is the index in [b] matched to a[i], or -1. */
    private fun align(a: List<String>, b: List<String>): IntArray {
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val result = IntArray(a.size) { -1 }
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { result[i] = j; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
        return result
    }

    private fun buildPattern(
        tokens: List<Token>,
        template: Template,
        valueFirst: Int,
        valueLast: Int,
        contextWords: Int
    ): String {
        fun piece(i: Int): String = when {
            tokens[i].kind != Kind.WORD && tokens[i].kind != Kind.PUNCT -> "(?:${kindRegex(tokens[i].kind)})"
            template.stable[i] -> literal(tokens[i].text)
            else -> GAP
        }
        fun isAnchor(i: Int) = tokens[i].kind == Kind.WORD && template.stable[i]

        // Walk outwards from the value until enough fixed words are collected
        val left = mutableListOf<String>()
        var anchors = 0
        var i = valueFirst - 1
        if (i >= 0 && template.gapAfter[i]) left += GAP
        while (i >= 0 && anchors < contextWords) {
            left += piece(i)
            if (isAnchor(i)) anchors++
            if (i > 0 && template.gapAfter[i - 1]) left += GAP
            i--
        }
        left.reverse()
        val leftStart = left.indexOfFirst { it != GAP && !it.startsWith("(?:") }
        val leftPieces = if (anchors == 0 || leftStart < 0) emptyList() else left.drop(leftStart)

        val right = mutableListOf<String>()
        anchors = 0
        i = valueLast + 1
        if (template.gapAfter[valueLast] && i < tokens.size) right += GAP
        while (i < tokens.size && anchors < contextWords) {
            right += piece(i)
            if (isAnchor(i)) anchors++
            if (template.gapAfter[i] && i + 1 < tokens.size) right += GAP
            i++
        }
        val rightEnd = right.indexOfLast { it != GAP && !it.startsWith("(?:") }
        val rightPieces = if (anchors == 0 || rightEnd < 0) emptyList() else right.take(rightEnd + 1)

        val valueRegex = valueRegex(tokens, valueFirst, valueLast, hasRightAnchor = rightPieces.isNotEmpty())
        // A lazy value followed by a lazy gap would shrink to one character, so the value owns that span
        val afterValue = if (valueRegex.endsWith("+?")) rightPieces.dropWhile { it == GAP } else rightPieces

        return buildString {
            append("(?iu)")
            if (leftPieces.isEmpty() && rightPieces.isEmpty()) append("^\\s*")
            leftPieces.collapseGaps().forEach { append(it).append("\\s*") }
            append("(").append(valueRegex).append(")")
            afterValue.collapseGaps().forEach { append("\\s*").append(it) }
            if (leftPieces.isEmpty() && rightPieces.isEmpty()) append("\\s*$")
        }
    }

    private fun valueRegex(tokens: List<Token>, first: Int, last: Int, hasRightAnchor: Boolean): String {
        val single = tokens[first].takeIf { first == last }
        return when {
            single != null && single.kind != Kind.WORD && single.kind != Kind.PUNCT -> kindRegex(single.kind)
            // Amounts written as "12.312,50 TL" when the currency is part of the selection
            single == null && last == first + 1 && tokens[first].kind == Kind.NUMBER && tokens[last].kind == Kind.WORD ->
                "$NUMBER_RE\\s*${literal(tokens[last].text)}"
            // A single word with digits in it is a code (tracking number, reference)
            single != null && single.text.any { it.isDigit() } -> CODE_RE
            // Names and free text can span any number of words, so run up to the right anchor
            hasRightAnchor -> "[^\\n]+?"
            else -> "[^\\n.,;!?]+"
        }
    }

    private fun literal(text: String) = Regex.escape(text)

    private fun List<String>.collapseGaps(): List<String> =
        filterIndexed { index, piece -> !(piece == GAP && getOrNull(index - 1) == GAP) }
}
