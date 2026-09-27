package com.example.capstone.domain.grading

/**
 * Spots a reply stuck in a loop while it is still being generated, so the call can stop.
 *
 * Seen on device (session 7, Qwen2-VL 2B, near-greedy sampling): FEEDBACK repeated one
 * 67-character clause ("correctly applied the quadratic formula to find the solutions, and ")
 * about 220 times, until the 4096-token context ran out. That took 274 s for one box and lost
 * the CONFIDENCE line, so the box went to review anyway.
 *
 * The rule: the text ends in a run where one piece of [MIN_PERIOD]..[MAX_PERIOD] characters
 * repeats back to back, covering at least [REPEATS] copies. A mark, a transcript or a sentence
 * of feedback does not do that; working such as "x = 1, x = 1, x = 1" is shorter than
 * [MIN_PERIOD] a copy, and three copies of a whole 24-character line is already a loop.
 */
object RunawayGuard {
    const val MIN_PERIOD = 24
    const val MAX_PERIOD = 400
    const val REPEATS = 3

    /** Appended to a reply that was cut off, so the stored raw reply says so. */
    const val CUT_MARKER = "\n[cut off by the app: the reply kept repeating itself]"

    /**
     * Where to cut [text] if it ends in a loop: the length that keeps everything before the
     * loop plus **one** copy of the repeated piece. Null when it is not looping.
     */
    fun cutLength(text: CharSequence): Int? {
        val n = text.length
        val maxP = minOf(MAX_PERIOD, n / REPEATS)
        for (p in MIN_PERIOD..maxP) {
            // Length of the suffix in which every character equals the one p before it.
            var run = 0
            var i = n - 1
            while (i - p >= 0 && text[i] == text[i - p]) {
                run++
                i--
            }
            if (run >= (REPEATS - 1) * p) {
                val periodicLength = run + p
                return n - periodicLength + p
            }
        }
        return null
    }

    /**
     * Adds one streamed piece of a reply to [acc]. Each message of LiteRT-LM's
     * `sendMessageAsync` is taken to be the next piece (UNVERIFIED which it sends); one that
     * repeats everything so far and more is taken as the whole reply instead, so both kinds
     * come out right.
     */
    fun appendStreamed(acc: StringBuilder, chunk: String) {
        if (acc.isNotEmpty() && chunk.length > acc.length && chunk.startsWith(acc)) acc.setLength(0)
        acc.append(chunk)
    }

    fun wasCutOff(reply: String): Boolean = reply.endsWith(CUT_MARKER)

    // A SCORE or CONFIDENCE line counts only with something after the colon, so the early
    // stop never ends a reply before both have a value (session 7 rule a). Two-turn grading's
    // second reply gives PARTS instead of SCORE (session 9).
    private val SCORE_LINE = Regex("(?im)^\\s*(?:SCORE|PARTS):[ \\t]*\\S")
    private val CONFIDENCE_LINE = Regex("(?im)^\\s*CONFIDENCE:[ \\t]*\\S")
    private val FEEDBACK_LINE = Regex("(?im)^\\s*FEEDBACK:[ \\t]*(\\S)")

    /**
     * Where the reply can stop: the end of the first FEEDBACK line, once it has text and
     * SCORE and CONFIDENCE both came **before** it (the phone's order, [GRADING_SYSTEM_PROMPT]).
     * Nothing after that line is used. Null while that point has not been reached, or when
     * the model put CONFIDENCE after FEEDBACK, so a reply in the old order is never cut short.
     */
    fun feedbackLineEnd(text: CharSequence): Int? {
        val feedback = feedbackAfterMark(text) ?: return null
        val end = text.indexOf('\n', feedback.groups[1]!!.range.first)
        return if (end < 0) null else end
    }

    /**
     * True when [text] has a FEEDBACK line with text, and SCORE and CONFIDENCE both came
     * before it. For a reply cut off by a loop, that means the loop was in the feedback, so
     * the mark and the confidence were written before it started.
     */
    fun markCameBeforeFeedback(text: CharSequence): Boolean = feedbackAfterMark(text) != null

    private fun feedbackAfterMark(text: CharSequence): MatchResult? {
        val feedback = FEEDBACK_LINE.find(text) ?: return null
        val before = text.subSequence(0, feedback.range.first)
        if (SCORE_LINE.find(before) == null || CONFIDENCE_LINE.find(before) == null) return null
        return feedback
    }

    /** [text] with its loop cut out and [CUT_MARKER] appended, or [text] unchanged. */
    fun cut(text: String): String = cutLength(text)?.let { text.substring(0, it) + CUT_MARKER } ?: text
}
