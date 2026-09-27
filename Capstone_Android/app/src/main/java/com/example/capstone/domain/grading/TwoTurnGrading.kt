package com.example.capstone.domain.grading

/*
 * DELIBERATE DIFFERENCE from the web end (session 9, [GradingConfig.twoTurn], debug switch):
 * the phone can mark in two separate conversations instead of one.
 *
 * Why: in the session 9 benchmark Gemma 4 E2B transcribed parts a) and b) of a partial answer
 * correctly, then added a part c) that was only in the model answer, and gave full marks at
 * confidence 95-100. With the model answer out of the conversation that reads the image, it
 * has nothing to copy from.
 *
 * - Turn 1 ([TRANSCRIBE_SYSTEM_PROMPT]): the student's crops and the question text only. No
 *   model answer, no marking scheme, no question figures. An exact transcript, one labelled
 *   block per part, NOT ANSWERED for a part with no writing.
 * - Turn 2 ([MARK_SYSTEM_PROMPT]): a new, text-only conversation with the question, the model
 *   answer (which carries any marking scheme) and turn 1's transcript. Reply `PARTS: a=<mark>
 *   b=<mark> ...`, then CONFIDENCE, then FEEDBACK.
 * - The phone adds the parts up. A part turn 1 wrote as NOT ANSWERED counts 0 whatever turn 2
 *   says.
 */

/** A labelled part of a question ("a", "b", ...), with its marks when the question states them. */
data class QuestionPart(val label: String, val maxMarks: Double?)

/** Finds a question's parts in its text: `a) … (Marks: 5) b) … c) …`. */
object QuestionParts {
    /** The one "part" of a question without labelled parts. */
    const val WHOLE = "answer"

    // "a)" or "(a)", a..h, not glued to a word or an opening bracket ("f(a)", "(formula)").
    private val LABEL = Regex("(?<![\\p{L}\\p{N}(])\\(?([a-h])\\)")
    private val MARKS = listOf(
        Regex("(?i)marks?\\s*:\\s*(\\d+(?:\\.\\d+)?)"),
        Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*marks?\\b")
    )

    /**
     * The parts in order: labels a, b, c … as they first appear in sequence, each with the
     * marks stated between it and the next label. Fewer than two means one [WHOLE] part.
     */
    fun detect(questionText: String): List<QuestionPart> {
        val found = mutableListOf<MatchResult>()
        var expected = 'a'
        for (m in LABEL.findAll(questionText)) {
            if (m.groupValues[1][0] == expected) {
                found += m
                expected++
            }
        }
        if (found.size < 2) return listOf(QuestionPart(WHOLE, null))
        return found.mapIndexed { i, m ->
            val end = found.getOrNull(i + 1)?.range?.first ?: questionText.length
            QuestionPart(m.groupValues[1], marksIn(questionText.substring(m.range.last + 1, end)))
        }
    }

    private fun marksIn(segment: String): Double? =
        MARKS.firstNotNullOfOrNull { it.find(segment)?.groupValues?.get(1)?.toDoubleOrNull() }
}

/** What turn 1 wrote for one part. */
data class PartTranscript(val label: String, val text: String) {
    /** NOT ANSWERED, NOTHING WRITTEN, or nothing at all after the label. */
    val notAnswered: Boolean get() = normalised.isEmpty() || normalised in NOT_ANSWERED_WORDS
    val unreadable: Boolean get() = normalised == "UNREADABLE"

    private val normalised: String
        get() = text.trim().trimEnd('.', '!').trim().uppercase()

    companion object {
        const val NOT_ANSWERED = "NOT ANSWERED"
        private val NOT_ANSWERED_WORDS = setOf(NOT_ANSWERED, "NOTHING WRITTEN", "[NOT ANSWERED]", "(NOT ANSWERED)")
    }
}

object TwoTurnParser {
    private val TRANSCRIPT_PREFIX = Regex("(?i)^\\s*TRANSCRIPT\\s*:\\s*")
    private val PART_LINE = Regex("(?i)^\\s*(?:part\\s*)?\\(?([a-h])\\s*[).:]\\s?(.*)$")
    private val PARTS_LINE = Regex("(?im)^\\s*PARTS\\s*:[ \\t]*(.+)$")
    private val PAIR = Regex("(?i)\\(?([a-z]+)\\)?\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)")
    private val NUMBER = Regex("-?\\d+(?:\\.\\d+)?")
    private val FEEDBACK = Regex("(?is)FEEDBACK\\s*:\\s*(.*)")

    /**
     * Turn 1's reply per part. For a question with parts: a line starting `a)` / `(a)` / `a.`
     * / `a:` begins that part, and later lines belong to it until the next label; text before
     * the first label is ignored. For a [QuestionParts.WHOLE] question: everything after
     * `TRANSCRIPT:` (or the whole reply). A part with no line of its own is absent.
     */
    fun transcript(reply: String, parts: List<QuestionPart>): Map<String, PartTranscript> {
        if (parts.size == 1 && parts[0].label == QuestionParts.WHOLE) {
            val text = reply.replace(TRANSCRIPT_PREFIX, "").trim()
            return mapOf(QuestionParts.WHOLE to PartTranscript(QuestionParts.WHOLE, text))
        }
        val labels = parts.map { it.label }.toSet()
        val blocks = linkedMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (raw in reply.lines()) {
            val line = raw.replace(TRANSCRIPT_PREFIX, "")
            val m = PART_LINE.find(line)
            val label = m?.groupValues?.get(1)?.lowercase()
            if (label != null && label in labels && label !in blocks) {
                // The student's own "a)" copied after the prompt's (on device: "a) a) …",
                // "c) c) UNREADABLE") is the same label once more, not part of the answer.
                val text = m.groupValues[2].trim().replace(repeatedLabel(label), "")
                current = StringBuilder(text).also { blocks[label] = it }
            } else if (current != null && line.isNotBlank()) {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line.trim())
            }
        }
        return blocks.mapValues { (label, text) -> PartTranscript(label, text.toString()) }
    }

    private fun repeatedLabel(label: String) = Regex("(?i)^\\(?${Regex.escape(label)}\\s*[).:]\\s*")

    /** The transcript as posted and shown: one block per part, in question order. */
    fun transcriptText(parts: List<QuestionPart>, transcript: Map<String, PartTranscript>): String =
        if (parts.size == 1 && parts[0].label == QuestionParts.WHOLE) {
            transcript[QuestionParts.WHOLE]?.text.orEmpty()
        } else {
            parts.joinToString("\n") { p -> "${p.label}) ${transcript[p.label]?.text ?: ""}".trimEnd() }
        }

    sealed interface Marks {
        data class Valid(val byPart: Map<String, Double>) : Marks
        data class Invalid(val why: String) : Marks
    }

    /**
     * Turn 2's `PARTS:` line (the last one). Every part exactly once, no others, each a
     * number in 0..its stated marks (or 0..[maxScore] when the question states none).
     * A one-part question may give just the number.
     */
    fun marks(reply: String, parts: List<QuestionPart>, maxScore: Int): Marks {
        val line = PARTS_LINE.findAll(reply).lastOrNull()?.groupValues?.get(1)
            ?: return Marks.Invalid("no PARTS line")
        val pairs = PAIR.findAll(line).map { it.groupValues[1].lowercase() to it.groupValues[2] }.toList()
        val byPart = linkedMapOf<String, Double>()
        if (pairs.isEmpty() && parts.size == 1) {
            val n = NUMBER.find(line)?.value?.toDoubleOrNull() ?: return Marks.Invalid("no mark on the PARTS line")
            byPart[parts[0].label] = n
        } else {
            val labels = parts.map { it.label }
            for ((label, value) in pairs) {
                if (label !in labels) return Marks.Invalid("PARTS has a part the question does not: $label")
                if (label in byPart) return Marks.Invalid("PARTS gives part $label twice")
                byPart[label] = value.toDoubleOrNull() ?: return Marks.Invalid("part $label is not a number")
            }
            val missing = labels.filter { it !in byPart }
            if (missing.isNotEmpty()) return Marks.Invalid("PARTS has no mark for ${missing.joinToString()}")
        }
        for (p in parts) {
            val mark = byPart.getValue(p.label)
            val cap = p.maxMarks ?: maxScore.toDouble()
            if (mark < 0 || mark > cap) return Marks.Invalid("part ${p.label}: ${fmt(mark)} is outside 0..${fmt(cap)}")
        }
        return Marks.Valid(byPart)
    }

    /** Turn 2's feedback: everything after the first FEEDBACK:, trimmed. */
    fun feedback(reply: String): String = FEEDBACK.find(reply)?.groupValues?.get(1)?.trim().orEmpty()

    internal fun fmt(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}

/** Turn 1: read the image, with the question for context, and nothing to copy an answer from. */
const val TRANSCRIBE_SYSTEM_PROMPT: String =
    "You are reading a student's handwritten exam answer.\n" +
        "\n" +
        "You are given the question and an image of what the student wrote. You are NOT given " +
        "the correct answer. Do not solve the question, do not mark it and do not add anything.\n" +
        "\n" +
        "Transcribe exactly what appears in the image. Copy only marks that are actually there. " +
        "Do not complete, correct or infer any step. If there is writing you cannot make out, " +
        "write UNREADABLE for it. If there is no writing for something, write NOT ANSWERED, " +
        "not UNREADABLE.\n"

/** Turn 1's reply format, after the question. */
fun transcribeUserMessage(questionText: String, parts: List<QuestionPart>): String = buildString {
    append("QUESTION:\n")
    append(pyStrip(questionText).ifEmpty { "(not provided)" })
    append("\n\nIMAGES: the image(s) are the STUDENT'S handwritten answer, in order.\n\n")
    if (parts.size == 1 && parts[0].label == QuestionParts.WHOLE) {
        append("Reply in exactly this format and nothing else:\n")
        append("TRANSCRIPT: <exactly what is written, or NOT ANSWERED if nothing is written>")
    } else {
        val labels = parts.joinToString(", ") { "${it.label})" }
        append("The question has parts $labels. Reply with one block per part, in order, and nothing else. ")
        append("Start each block on a new line with the part's label; its working may continue on the next lines. ")
        append("Write ${PartTranscript.NOT_ANSWERED} for a part the student did not write anything for. ")
        append("Write UNREADABLE only for writing that is there but cannot be made out.\n")
        parts.forEach { append("${it.label}) <exactly what the student wrote for part ${it.label}, or ${PartTranscript.NOT_ANSWERED}>\n") }
    }
}

/** Turn 2: mark a transcript against the model answer. No image. */
const val MARK_SYSTEM_PROMPT: String =
    "You are marking a student's exam answer from a transcript of their handwriting.\n" +
        "\n" +
        "You are given the question, the official model answer, and the TRANSCRIPT of what the " +
        "student actually wrote. Mark only what is in the transcript. The model answer is for " +
        "comparison only: never credit a step or a part that appears only in the model answer. " +
        "A part written as NOT ANSWERED gets 0.\n" +
        "\n" +
        "Mark the reasoning, not the handwriting: ignore untidiness, crossings-out and spelling.\n" +
        "\n" +
        "If the model answer sets out a marking scheme — a breakdown of how many marks each step " +
        "or criterion is worth — follow it exactly. If it sets out no scheme, award partial credit " +
        "for work that is correct as far as it goes, and full marks for a different but valid " +
        "method. Never give a part more than its marks.\n"

/** Turn 2's user message: question, model answer, marks, transcript, and the reply format. */
fun markUserMessage(item: AnswerToGrade, parts: List<QuestionPart>, transcript: String): String = buildString {
    append("QUESTION:\n").append(pyStrip(item.questionText).ifEmpty { "(not provided)" })
    append("\n\nMODEL ANSWER:\n").append(pyStrip(item.groundTruthText))
    append("\n\nMAXIMUM MARK: ${item.maxScore}")
    if (parts.any { it.maxMarks != null }) {
        append("\nMARKS PER PART: ")
        append(parts.joinToString(", ") { p -> "${p.label}) ${p.maxMarks?.let { TwoTurnParser.fmt(it) } ?: "?"}" })
    }
    append("\n\nTRANSCRIPT OF THE STUDENT'S ANSWER:\n").append(transcript)
    append("\n\nReply in exactly this format and nothing else:\n")
    append("PARTS: ").append(parts.joinToString(" ") { "${it.label}=<mark>" }).append('\n')
    append(CONFIDENCE_FORMAT_LINE)
    append("FEEDBACK: <one or two sentences addressed to the student>")
}
