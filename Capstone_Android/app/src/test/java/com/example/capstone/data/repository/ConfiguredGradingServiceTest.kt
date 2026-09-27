package com.example.capstone.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.capstone.data.local.GradingDebugSettings
import com.example.capstone.data.local.ModelSpec
import com.example.capstone.domain.grading.AnswerToGrade
import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.BoxStatus
import com.example.capstone.domain.grading.GradingConfig
import com.example.capstone.domain.grading.GradingService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Real grading reads the default (session 9b): Gemma 4 E2B, two-turn, in every build. */
class ConfiguredGradingServiceTest {

    private val item = AnswerToGrade(answerBoxId = "b1", label = "", maxScore = 5, questionText = "q", groundTruthText = "m")

    private class Seen {
        val models = mutableListOf<ModelSpec>()
        val configs = mutableListOf<GradingConfig>()
    }

    private fun service(settings: GradingDebugSettings, seen: Seen) = ConfiguredGradingService(
        settings = settings,
        confidenceThreshold = 60.0,
        useModel = { seen.models += it },
        grader = { config ->
            seen.configs += config
            object : GradingService {
                override suspend fun grade(item: AnswerToGrade) = BoxGradeResult(
                    item.answerBoxId, 5.0, 5, null, null, 90.0, BoxStatus.GRADED, null, null, "FAKE", 1
                )
            }
        }
    )

    private fun prefs() = PreferenceDataStoreFactory.create(
        produceFile = { File.createTempFile("grading_settings", ".preferences_pb").apply { delete() } }
    )

    @Test
    fun `with nothing saved, a debug build grades with Gemma 4 E2B in two turns`() = runBlocking {
        val seen = Seen()
        service(GradingDebugSettings(prefs(), debugBuild = true), seen).grade(item)
        assertThat(seen.models).containsExactly(ModelSpec.GEMMA4_E2B)
        assertThat(seen.configs.single().twoTurn).isTrue()
        assertThat(seen.configs.single().trimCrops).isFalse()
        assertThat(seen.configs.single().confidenceThreshold).isEqualTo(60.0)
    }

    @Test
    fun `a release build grades with Gemma 4 E2B in two turns whatever is saved`() = runBlocking {
        val prefs = prefs()
        GradingDebugSettings(prefs, debugBuild = true).apply {
            setPlainGrading(true)
            setTrimCrops(true)
        }
        val seen = Seen()
        service(GradingDebugSettings(prefs, debugBuild = false), seen).grade(item)
        assertThat(seen.models).containsExactly(ModelSpec.GEMMA4_E2B)
        assertThat(seen.configs.single().twoTurn).isTrue()
        assertThat(seen.configs.single().trimCrops).isFalse()
    }

    @Test
    fun `plain is a debug option, read on the next box`() = runBlocking {
        val settings = GradingDebugSettings(prefs(), debugBuild = true)
        val seen = Seen()
        val service = service(settings, seen)
        service.grade(item)
        settings.setPlainGrading(true)
        service.grade(item)
        assertThat(seen.configs.map { it.twoTurn }).containsExactly(true, false).inOrder()
    }
}
