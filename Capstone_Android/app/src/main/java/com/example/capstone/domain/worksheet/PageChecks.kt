package com.example.capstone.domain.worksheet

import com.example.capstone.data.remote.PageExtractionResultDto
import com.example.capstone.extractor.Layout

/** One (answer box, part) printed on a page: what both extractors should cut from it. */
data class PagePiece(val answerBoxId: String, val part: Int)

/** What the server's extractor made of one uploaded page. */
sealed interface ServerVerdict {
    /** One line to show the student, e.g. "4/4 corner markers, 3 answer boxes cut, 2 of 3 codes read". */
    val summary: String

    data class Accepted(override val summary: String) : ServerVerdict

    /** The server could not use the page; the student takes it again. [reason] says why. */
    data class Refused(override val summary: String, val reason: String) : ServerVerdict
}

/**
 * Checks on a page's upload and on the student's script as a whole.
 *
 * The server's answer is judged against the pack's geometry, not taken at its word: it
 * returns 200 with an `error` field, or with fewer markers, when it could not crop
 * (`services/extractor.py`, `extract_page`).
 */
object PageChecks {

    /** The pieces printed on [pageIndex], in (order index, part) order. */
    fun expectedPieces(layout: Layout, pageIndex: Int): List<PagePiece> =
        layout.partsOnPage(pageIndex).map { (box, part) -> PagePiece(box.externalAnswerBoxId, part) }

    /**
     * Whether the server could read [page].
     *
     * Refused when it reported an error, found fewer than four markers, cut a different set
     * of pieces than the pack prints on that page, or read box codes that all belong
     * somewhere else. The last one is how a photo of the wrong page shows up: the app sends
     * an explicit page number, so the server crops that page's boxes whatever the photo is,
     * and only the QR check (`_check_qr`) says the codes there are another box's.
     *
     * A code that could not be read ("absent") is normal on a phone photo and never refuses.
     * Neither does a single mismatch among matches: the server's search window around a box
     * can catch its neighbour's code.
     */
    fun serverVerdict(expected: List<PagePiece>, page: PageExtractionResultDto): ServerVerdict {
        val passed = page.crops.count { it.qrCheck == "pass" }
        val failed = page.crops.count { it.qrCheck == "fail" }
        val summary = buildString {
            append(page.markersDetected).append(" corner markers")
            append(", ").append(page.crops.size).append(if (page.crops.size == 1) " answer box" else " answer boxes")
            append(" cut")
            if (page.crops.isNotEmpty()) {
                append(", ").append(passed).append(" of ").append(page.crops.size).append(" codes read")
            }
        }

        page.error?.let { return ServerVerdict.Refused(summary, it) }
        if (page.markersDetected != "4/4") {
            return ServerVerdict.Refused(
                summary,
                "The server found ${page.markersDetected} corner markers. All four must be in the photo."
            )
        }
        val got = page.crops.map { PagePiece(it.answerBoxId, it.part) }.toSet()
        if (got != expected.toSet()) {
            return ServerVerdict.Refused(
                summary,
                "The server cut different answer boxes from this page than the paper prints on it."
            )
        }
        if (failed > 0 && passed == 0) {
            return ServerVerdict.Refused(
                summary,
                "The codes on this photo belong to other answer boxes. Is it the right page?"
            )
        }
        return ServerVerdict.Accepted(summary)
    }

    /**
     * Why the script cannot be handed in yet, or null when it can.
     *
     * Every page that has answer boxes on it must be on the server, accepted, and cropped on
     * this phone. Pages with no answer boxes need no photo.
     */
    fun handInProblem(requiredPages: List<Int>, readyPages: Set<Int>): String? {
        val missing = requiredPages.filterNot { it in readyPages }
        if (missing.isEmpty()) return null
        val names = missing.joinToString { (it + 1).toString() }
        return if (missing.size == 1) "Page $names still needs a photo." else "Pages $names still need photos."
    }

    /**
     * The phone's saved pages that no longer belong to anything on the server, and must go.
     *
     * The phone saves a page's crops only after the web end accepted its photo, so a saved
     * page the server does not hold is left over: the teacher deleted the submission
     * ([serverPages] null: there is none) or that page. Grading such crops would mark a
     * script that no longer exists.
     */
    fun stalePhonePages(phonePages: Set<Int>, serverPages: Set<Int>?): Set<Int> =
        if (serverPages == null) phonePages else phonePages - serverPages
}
