package com.example.capstone.domain.worksheet

import com.example.capstone.domain.model.Assignment
import com.example.capstone.domain.model.Question
import com.example.capstone.extractor.AnswerBoxRef
import com.example.capstone.extractor.AnswerCrop
import com.example.capstone.extractor.Bbox
import com.example.capstone.extractor.Layout
import com.example.capstone.extractor.MarkerRef
import com.example.capstone.extractor.Segment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [QuestionResolver] - the single join between the pack's
 * answer box ids and this app's question ids, part by part.
 *
 * Plain JVM: the resolver touches no Android API and no OpenCV, on purpose, so
 * the join can be tested without a device.
 */
class QuestionResolverTest {

    private val questionId = "q_worksheet_3"

    private fun question(
        id: Int,
        boxId: String?,
        text: String = "Q$id"
    ) = Question(
        id = id,
        text = text,
        marks = 5,
        modelAnswer = "answer",
        rubric = null,
        externalAnswerBoxId = boxId
    )

    /**
     * [parts] gives a box more than one segment (one per page); every other
     * linked box is printed in one piece. [withLayout] false leaves the layout out.
     */
    private fun assignment(
        vararg questions: Question,
        externalQuestionId: String? = this.questionId,
        parts: Map<String, Int> = emptyMap(),
        withLayout: Boolean = true
    ) = Assignment(
        id = "12",
        title = "Worksheet 3",
        description = null,
        questions = questions.toList(),
        externalQuestionId = externalQuestionId,
        layout = if (!withLayout) null else Layout(
            externalQuestionId = externalQuestionId ?: "none",
            pageWidthPx = 1240,
            pageHeightPx = 1754,
            markers = listOf(
                MarkerRef(0, 70.0, 70.0),
                MarkerRef(1, 1170.0, 70.0),
                MarkerRef(2, 70.0, 1684.0),
                MarkerRef(3, 1170.0, 1684.0)
            ),
            answerBoxes = questions.mapNotNull { it.externalAnswerBoxId?.takeIf(String::isNotBlank) }
                .distinct()
                .mapIndexed { i, boxId ->
                    AnswerBoxRef(
                        boxId,
                        i,
                        List(parts[boxId] ?: 1) { page -> Segment(page, Bbox(186, 334, 930, 300)) }
                    )
                },
            arucoDictionary = Layout.SUPPORTED_ARUCO_DICTIONARY
        )
    )

    private fun crop(
        boxId: String,
        pageIndex: Int = 0,
        orderIndex: Int = 0,
        externalQuestionId: String = this.questionId,
        part: Int = 0
    ) = AnswerCrop(
        externalQuestionId = externalQuestionId,
        externalAnswerBoxId = boxId,
        part = part,
        pageIndex = pageIndex,
        orderIndex = orderIndex,
        png = byteArrayOf(1, 2, 3),
        imageQuad = emptyList()
    )

    private fun resolverFor(assignment: Assignment): QuestionResolver {
        val creation = QuestionResolver.forAssignment(assignment)
        assertThat(creation).isInstanceOf(ResolverCreation.Available::class.java)
        return (creation as ResolverCreation.Available).resolver
    }

    // ---- construction -----------------------------------------------------

    @Test
    fun `builds a resolver for a fully linked assignment`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_one"), question(2, "ab_two"))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Available::class.java)
        val resolver = (creation as ResolverCreation.Available).resolver
        assertThat(resolver.assignmentId).isEqualTo("12")
        assertThat(resolver.externalQuestionId).isEqualTo(questionId)
        assertThat(resolver.knownBoxIds).containsExactly("ab_one", "ab_two")
    }

    @Test
    fun `refuses an assignment with no question id`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, null), externalQuestionId = null)
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason)
            .contains("no question id")
    }

    @Test
    fun `refuses an assignment with no printed layout`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_one"), withLayout = false)
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason).contains("no printed layout")
    }

    @Test
    fun `refuses an assignment with no questions`() {
        val creation = QuestionResolver.forAssignment(assignment())

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason)
            .contains("no questions")
    }

    @Test
    fun `refuses an imported assignment carrying no answer box ids`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, null), question(2, null))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason)
            .contains("carries no answer box ids")
    }

    @Test
    fun `refuses a partially linked assignment rather than dropping the unlinked`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_one"), question(2, null))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        val reason = (creation as ResolverCreation.Unavailable).reason
        assertThat(reason).contains("partially linked")
        assertThat(reason).contains("[2]")
    }

    @Test
    fun `treats a blank answer box id as unlinked`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_one"), question(2, "   "))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
    }

    @Test
    fun `refuses two questions sharing one answer box id`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_dup"), question(2, "ab_dup"))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason)
            .contains("ambiguous")
    }

    // ---- resolution -------------------------------------------------------

    @Test
    fun `resolves a complete one to one crop set`() {
        val resolver = resolverFor(assignment(question(1, "ab_one"), question(2, "ab_two")))

        val outcome = resolver.resolve(
            listOf(crop("ab_one"), crop("ab_two", pageIndex = 1, orderIndex = 1))
        )

        assertThat(outcome).isInstanceOf(Resolution.Resolved::class.java)
        val answers = (outcome as Resolution.Resolved).answers
        assertThat(answers.map { it.question.id }).containsExactly(1, 2).inOrder()
        assertThat(answers.map { it.answerBoxId })
            .containsExactly("ab_one", "ab_two").inOrder()
    }

    @Test
    fun `returns answers in question order regardless of crop order`() {
        val resolver = resolverFor(
            assignment(question(1, "ab_one"), question(2, "ab_two"), question(3, "ab_three"))
        )

        val outcome = resolver.resolve(
            listOf(crop("ab_three"), crop("ab_one"), crop("ab_two"))
        )

        assertThat(outcome).isInstanceOf(Resolution.Resolved::class.java)
        assertThat((outcome as Resolution.Resolved).answers.map { it.question.id })
            .containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `fails when a crop has no question`() {
        val resolver = resolverFor(assignment(question(1, "ab_one")))

        val outcome = resolver.resolve(listOf(crop("ab_one"), crop("ab_stranger")))

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.cropsWithoutQuestion).containsExactly("ab_stranger")
        assertThat(failed.questionsWithoutCrop).isEmpty()
        assertThat(failed.message).contains("ab_stranger")
    }

    @Test
    fun `fails when a question has no crop`() {
        val resolver = resolverFor(assignment(question(1, "ab_one"), question(2, "ab_two")))

        val outcome = resolver.resolve(listOf(crop("ab_one")))

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.questionsWithoutCrop).containsExactly(2)
        assertThat(failed.cropsWithoutQuestion).isEmpty()
        assertThat(failed.message).contains("[2]")
    }

    @Test
    fun `fails on an empty crop list rather than resolving nothing`() {
        val resolver = resolverFor(assignment(question(1, "ab_one")))

        val outcome = resolver.resolve(emptyList())

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        assertThat((outcome as Resolution.Failed).questionsWithoutCrop).containsExactly(1)
    }

    @Test
    fun `fails on a duplicated crop`() {
        val resolver = resolverFor(assignment(question(1, "ab_one"), question(2, "ab_two")))

        val outcome = resolver.resolve(
            listOf(crop("ab_one"), crop("ab_one", pageIndex = 1), crop("ab_two"))
        )

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.duplicateCropIds).containsExactly("ab_one")
        assertThat(failed.message).contains("duplicate")
    }

    @Test
    fun `rejects a crop whose box id matches but whose question id does not`() {
        // The join key is the pair. A bare answer box id is globally unique on
        // the teacher side only by accident, so a matching box id from another
        // worksheet must not resolve - it must fail loudly.
        val resolver = resolverFor(assignment(question(1, "ab_one")))

        val outcome = resolver.resolve(
            listOf(crop("ab_one", externalQuestionId = "q_some_other_worksheet"))
        )

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.cropsFromOtherQuestion)
            .containsExactly("q_some_other_worksheet")
        assertThat(failed.questionsWithoutCrop).containsExactly(1)
        assertThat(failed.message).contains("q_some_other_worksheet")
    }

    @Test
    fun `reports every category of mismatch at once`() {
        val resolver = resolverFor(assignment(question(1, "ab_one"), question(2, "ab_two")))

        val outcome = resolver.resolve(
            listOf(
                crop("ab_one"),
                crop("ab_one"),
                crop("ab_stranger"),
                crop("ab_two", externalQuestionId = "q_other")
            )
        )

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.duplicateCropIds).containsExactly("ab_one")
        assertThat(failed.cropsWithoutQuestion).containsExactly("ab_stranger")
        assertThat(failed.questionsWithoutCrop).containsExactly(2)
        assertThat(failed.cropsFromOtherQuestion).containsExactly("q_other")
        assertThat(failed.assignmentId).isEqualTo("12")
        assertThat(failed.externalQuestionId).isEqualTo(questionId)
    }

    @Test
    fun `carries the full question through to the resolved answer`() {
        val resolver = resolverFor(assignment(question(1, "ab_one", text = "State the law.")))

        val outcome = resolver.resolve(listOf(crop("ab_one")))

        val answer = (outcome as Resolution.Resolved).answers.single()
        assertThat(answer.question.text).isEqualTo("State the law.")
        assertThat(answer.question.marks).isEqualTo(5)
        assertThat(answer.question.modelAnswer).isEqualTo("answer")
        assertThat(answer.question.isGradeable).isTrue()
        assertThat(answer.crops.single().png).hasLength(3)
    }

    // ---- boxes printed over a page break ------------------------------------

    @Test
    fun `a box over two pages resolves with both parts, in part order`() {
        val resolver = resolverFor(
            assignment(question(1, "ab_one"), question(2, "ab_split"), parts = mapOf("ab_split" to 2))
        )

        // Page 2 photographed first.
        val outcome = resolver.resolve(
            listOf(
                crop("ab_split", pageIndex = 1, orderIndex = 1, part = 1),
                crop("ab_one"),
                crop("ab_split", pageIndex = 0, orderIndex = 1, part = 0)
            )
        )

        val answers = (outcome as Resolution.Resolved).answers
        assertThat(answers.map { it.answerBoxId }).containsExactly("ab_one", "ab_split").inOrder()
        assertThat(answers[1].crops.map { it.part }).containsExactly(0, 1).inOrder()
        assertThat(answers[1].crops.map { it.pageIndex }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `a box missing its second page fails, naming the part`() {
        val resolver = resolverFor(
            assignment(question(1, "ab_one"), question(2, "ab_split"), parts = mapOf("ab_split" to 2))
        )

        val outcome = resolver.resolve(listOf(crop("ab_one"), crop("ab_split", part = 0)))

        val failed = outcome as Resolution.Failed
        assertThat(failed.questionsWithoutCrop).containsExactly(2)
        assertThat(failed.missingParts).containsExactly("ab_split part 2")
        assertThat(failed.message).contains("ab_split part 2")
    }

    @Test
    fun `a part number the layout does not have is refused`() {
        val resolver = resolverFor(assignment(question(1, "ab_one")))

        val outcome = resolver.resolve(listOf(crop("ab_one"), crop("ab_one", pageIndex = 1, part = 1)))

        assertThat((outcome as Resolution.Failed).cropsWithoutQuestion).containsExactly("ab_one part 2")
    }

    @Test
    fun `the same part twice is a duplicate`() {
        val resolver = resolverFor(assignment(question(1, "ab_split"), parts = mapOf("ab_split" to 2)))

        val outcome = resolver.resolve(
            listOf(
                crop("ab_split", part = 0),
                crop("ab_split", pageIndex = 1, part = 1),
                crop("ab_split", pageIndex = 1, part = 1)
            )
        )

        assertThat((outcome as Resolution.Failed).duplicateCropIds).containsExactly("ab_split part 2")
    }
}
