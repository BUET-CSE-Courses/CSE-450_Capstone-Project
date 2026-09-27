package com.example.capstone.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GradingDebugSettingsTest {

    @Test
    fun `Gemma 4 E2B is the default and the only grading choice (session 9b)`() {
        assertThat(ModelSpec.DEFAULT).isEqualTo(ModelSpec.GEMMA4_E2B)
        assertThat(ModelSpec.GRADING_CHOICES).containsExactly(ModelSpec.GEMMA4_E2B)
        assertThat(GradingDebugSettings.resolveModel(debugBuild = true, saved = null)).isEqualTo(ModelSpec.GEMMA4_E2B)
        assertThat(GradingDebugSettings.resolveModel(debugBuild = false, saved = null)).isEqualTo(ModelSpec.GEMMA4_E2B)
    }

    @Test
    fun `a saved Qwen choice falls back to Gemma`() {
        assertThat(GradingDebugSettings.resolveModel(debugBuild = true, saved = "QWEN2_VL_2B")).isEqualTo(ModelSpec.GEMMA4_E2B)
    }

    @Test
    fun `a debug build grades with the saved choice`() {
        assertThat(GradingDebugSettings.resolveModel(debugBuild = true, saved = "GEMMA4_E2B")).isEqualTo(ModelSpec.GEMMA4_E2B)
    }

    @Test
    fun `a release build ignores the saved choice, and an unknown or non-grading name falls back`() {
        assertThat(GradingDebugSettings.resolveModel(debugBuild = false, saved = "QWEN2_VL_2B")).isEqualTo(ModelSpec.DEFAULT)
        assertThat(GradingDebugSettings.resolveModel(debugBuild = true, saved = "NOPE")).isEqualTo(ModelSpec.DEFAULT)
        assertThat(GradingDebugSettings.resolveModel(debugBuild = true, saved = "GEMMA3_1B")).isEqualTo(ModelSpec.DEFAULT)
    }

    @Test
    fun `Gemma's spec is a vision model sized to the published file`() {
        val gemma = ModelSpec.GEMMA4_E2B
        assertThat(gemma.fileName).isEqualTo("gemma-4-E2B-it.litertlm")
        assertThat(gemma.expectedBytes).isEqualTo(2_588_147_712L)
        assertThat(gemma.supportsVision).isTrue()
        assertThat(gemma.maxNumTokens).isEqualTo(4096)
        assertThat(gemma.imageTokens).isEqualTo(280)
    }

    @Test
    fun `two-turn is on unless a debug build chooses plain, force re-mark is off unless a debug build turns it on`() = kotlinx.coroutines.runBlocking {
        val prefs = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            produceFile = { java.io.File.createTempFile("grading_settings", ".preferences_pb").apply { delete() } }
        )
        val debug = GradingDebugSettings(prefs, debugBuild = true)
        assertThat(debug.currentTwoTurn()).isTrue()
        assertThat(debug.currentForceRemark()).isFalse()
        debug.setPlainGrading(true)
        debug.setForceRemark(true)
        assertThat(debug.currentTwoTurn()).isFalse()
        assertThat(debug.currentForceRemark()).isTrue()
        // A release build reads the same file and ignores both: two-turn, no forced re-mark.
        val release = GradingDebugSettings(prefs, debugBuild = false)
        assertThat(release.currentTwoTurn()).isTrue()
        assertThat(release.currentForceRemark()).isFalse()
    }
}
