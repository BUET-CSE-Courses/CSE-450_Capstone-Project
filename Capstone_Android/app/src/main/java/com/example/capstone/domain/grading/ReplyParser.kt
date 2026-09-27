package com.example.capstone.domain.grading

/*
 * Port of parse_grading_response and its regexes from
 * Script-Checker-Web-End/backend/services/grading.py:320-377 at commit c69eea2
 * (main), plus CONFIDENCE parsing, which is the phone's own addition.
 *
 * Python's `re` and Java's regex differ on str patterns, so the patterns are
 * spelt out rather than copied character for character:
 * - Python's `\s`, `\d` and `\W` are Unicode-aware. [PY_WS], `\p{Nd}` and
 *   [PY_NON_WORD] stand in for them, because Android's regex engine does not
 *   reliably support UNICODE_CHARACTER_CLASS.
 * - Python's `.` stops only at `\n` and `$` matches at the end or before one
 *   final `\n`. `[^\n]` and `(?=\n?\z)` say exactly that; Java's own `.` and
 *   `$` also stop at `\r`, U+0085, U+2028 and U+2029.
 * - Python's IGNORECASE on str patterns is Unicode-aware, hence `(?iu)`.
 */

/** Python's str.isspace() set, which is what `\s` and str.strip() use. */
private const val PY_WS =
    "[\\t\\n\\u000B\\u000C\\r\\u001C-\\u001F \\u0085\\u00A0\\u1680\\u2000-\\u200A" +
        "\\u2028\\u2029\\u202F\\u205F\\u3000]"

/** Python's `\W`: not a letter, not a number, not an underscore. */
private const val PY_NON_WORD = "[^\\p{L}\\p{N}_]"

private val SCORE_RE = Regex("(?iu)SCORE:$PY_WS*([^\\n]+)")
private val FEEDBACK_RE = Regex("(?ius)FEEDBACK:$PY_WS*(.+)")
private val TRANSCRIPT_RE = Regex("(?ius)TRANSCRIPT:$PY_WS*(.*?)(?=\\n$PY_WS*SCORE:|\\n?\\z)")

/** Phrasings a model reaches for when the page turns out to be empty. */
private val NOTHING_WRITTEN = Regex(
    "(?iu)^$PY_NON_WORD*(nothing written|nothing|none|blank|empty|no writing|no answer|n/?a|-+)" +
        "$PY_NON_WORD*(?=\\n?\\z)"
)

private val NUMBER_RE = Regex("-?\\p{Nd}+(?:\\.\\p{Nd}+)?")

/**
 * A CONFIDENCE line. Not preceded by a letter, digit or underscore, so
 * "LOWCONFIDENCE:" does not count. Runs to the end of its line.
 */
private val CONFIDENCE_RE = Regex("(?iu)(?<![\\p{L}\\p{N}_])CONFIDENCE:([^\\n]*)")

private val PY_WS_CHARS: Set<Char> =
    ("\t\n\u000B\u000C\r\u001C\u001D\u001E\u001F \u0085  " +
        "           " +
        "    　").toSet()

/** Python's str.strip() with no argument. */
internal fun pyStrip(s: String): String = s.trim { it in PY_WS_CHARS }

/** Python's float() of a `-?\d+(\.\d+)?` match, where `\d` may be any Unicode digit. */
private fun pyFloat(number: String): Double {
    val ascii = StringBuilder(number.length)
    var i = 0
    while (i < number.length) {
        val codePoint = number.codePointAt(i)
        val digit = Character.digit(codePoint, 10)
        if (digit >= 0) ascii.append('0' + digit) else ascii.appendCodePoint(codePoint)
        i += Character.charCount(codePoint)
    }
    return ascii.toString().toDouble()
}

/**
 * The server's dict {score, feedback, unreadable, parse_error}, plus
 * [transcript], which the server computes but does not return.
 */
data class ParsedGrade(
    val score: Double?,
    val feedback: String,
    val unreadable: Boolean,
    val parseError: Boolean,
    /** The TRANSCRIPT section, stripped, or null when the reply has none. */
    val transcript: String?
)

const val NOTHING_WRITTEN_FEEDBACK = "Nothing was written in this answer box."

/**
 * Port of `parse_grading_response`.
 *
 * A reply that can't be parsed, or a score outside 0..max, is reported rather
 * than coerced into a number. A wrong mark that looks confident is worse than
 * an obvious "needs a human".
 */
fun parseGradingResponse(text: String?, maxScore: Int): ParsedGrade {
    val raw = pyStrip(text ?: "")
    val feedbackMatch = FEEDBACK_RE.find(raw)
    var feedback = feedbackMatch?.let { pyStrip(it.groupValues[1]) } ?: ""
    // FEEDBACK is last in the format, so strip anything the model added after.
    feedback = pyStrip(feedback.substringBefore("SCORE:"))

    // A blank page must never earn marks, so the transcript is enforced here
    // rather than trusted to the prompt.
    val transcript = TRANSCRIPT_RE.find(raw)?.let { pyStrip(it.groupValues[1]) }
    if (transcript != null && (transcript.isEmpty() || NOTHING_WRITTEN.containsMatchIn(transcript))) {
        return ParsedGrade(
            score = 0.0,
            feedback = NOTHING_WRITTEN_FEEDBACK,
            unreadable = false,
            parseError = false,
            transcript = transcript
        )
    }

    val scoreMatch = SCORE_RE.find(raw)
        ?: return ParsedGrade(null, feedback, unreadable = false, parseError = true, transcript = transcript)

    val scoreText = pyStrip(pyStrip(scoreMatch.groupValues[1]).split("\n")[0])
    if (scoreText.uppercase().startsWith("UNREADABLE")) {
        return ParsedGrade(null, feedback, unreadable = true, parseError = false, transcript = transcript)
    }

    val number = NUMBER_RE.find(scoreText)
        ?: return ParsedGrade(null, feedback, unreadable = false, parseError = true, transcript = transcript)

    val score = pyFloat(number.value)
    if (score < 0 || score > maxScore) {
        return ParsedGrade(null, feedback, unreadable = false, parseError = true, transcript = transcript)
    }

    return ParsedGrade(score, feedback, unreadable = false, parseError = false, transcript = transcript)
}

/** What the CONFIDENCE line said. */
sealed interface Confidence {
    /** No CONFIDENCE line at all. */
    data object Missing : Confidence

    /** A CONFIDENCE line with no number on it. */
    data class Unparsable(val text: String) : Confidence

    /** A number outside 0..100. */
    data class OutOfRange(val value: Double) : Confidence

    data class Valid(val value: Double) : Confidence
}

/** A whole reply: the ported parse of everything except CONFIDENCE, and CONFIDENCE. */
data class ParsedReply(val grade: ParsedGrade, val confidence: Confidence)

const val CONFIDENCE_MIN = 0.0
const val CONFIDENCE_MAX = 100.0

/**
 * Parses the phone's reply format: the server's TRANSCRIPT / SCORE / FEEDBACK,
 * then CONFIDENCE.
 *
 * `_FEEDBACK_RE` is DOTALL and runs to the end of the reply, so a CONFIDENCE
 * line left in place would end up inside the feedback. Every CONFIDENCE line
 * is therefore cut out first and the rest goes to [parseGradingResponse]
 * unchanged. When there are several, the last one counts.
 */
fun parseReply(text: String?, maxScore: Int): ParsedReply {
    val raw = text ?: ""
    val matches = CONFIDENCE_RE.findAll(raw).toList()
    val withoutConfidence = if (matches.isEmpty()) raw else CONFIDENCE_RE.replace(raw, "")
    val grade = parseGradingResponse(withoutConfidence, maxScore)
    return ParsedReply(
        grade = if (grade.score != null && scoreIsAmbiguous(withoutConfidence, maxScore)) {
            grade.copy(score = null, parseError = true)
        } else grade,
        confidence = matches.lastOrNull()?.let { confidenceOf(it.groupValues[1]) } ?: Confidence.Missing
    )
}

/** The last CONFIDENCE line of [text], by the same rule as [parseReply]. */
fun parseConfidence(text: String?): Confidence =
    CONFIDENCE_RE.findAll(text ?: "").lastOrNull()?.let { confidenceOf(it.groupValues[1]) } ?: Confidence.Missing

/** "10/15", "10 / 15", "10 out of 15", optionally followed by "marks". */
private val SCORE_OF_MAX_RE = Regex(
    "(?iu)^(-?\\p{Nd}+(?:\\.\\p{Nd}+)?)$PY_WS*(?:/|out${PY_WS}+of)$PY_WS*(\\p{Nd}+(?:\\.\\p{Nd}+)?)\\b"
)

/**
 * Phone only (session 7), stricter than the server's "first number on the SCORE line":
 * a SCORE line with one number ("10", "10 marks", "4.5 (method)") is that number; with two it
 * must read "N/D" or "N out of D" where D is the box's [maxScore] ("10/15", "10 out of 15
 * marks"). Anything else with more numbers ("5 + 5 = 10", "10/20" on a 15-mark box) is
 * ambiguous, and the mark is not guessed. A reply with no SCORE line (the transcript said
 * NOTHING WRITTEN) is left to the port.
 */
internal fun scoreIsAmbiguous(textWithoutConfidence: String, maxScore: Int): Boolean {
    val line = Regex("(?iu)SCORE:$PY_WS*([^\\n]+)").find(textWithoutConfidence) ?: return false
    val scoreText = pyStrip(line.groupValues[1])
    val numbers = NUMBER_RE.findAll(scoreText).count()
    if (numbers <= 1) return false
    if (numbers > 2) return true
    val ofMax = SCORE_OF_MAX_RE.find(scoreText) ?: return true
    return pyFloat(ofMax.groupValues[2]) != maxScore.toDouble()
}

private fun confidenceOf(lineTail: String): Confidence {
    val value = pyStrip(lineTail)
    val number = NUMBER_RE.find(value) ?: return Confidence.Unparsable(value)
    val parsed = pyFloat(number.value)
    return if (parsed < CONFIDENCE_MIN || parsed > CONFIDENCE_MAX) {
        Confidence.OutOfRange(parsed)
    } else {
        Confidence.Valid(parsed)
    }
}
