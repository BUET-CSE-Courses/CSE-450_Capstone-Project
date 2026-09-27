package com.example.capstone.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * How real grading runs, with debug-build overrides (session 9): which model grades, whether
 * the model is sent each crop trimmed to the handwriting
 * ([com.example.capstone.domain.grading.CropTrim]), two-turn or plain grading
 * ([com.example.capstone.domain.grading.GradingConfig.twoTurn]), and "force server re-mark"
 * (every box posted as needing fallback, to test the server's self-hosted re-mark).
 *
 * Defaults, and all a release build ever uses (session 9b, the user's decision):
 * [ModelSpec.DEFAULT] (Gemma 4 E2B), **two-turn**, trim off, no forced re-mark. A debug build may
 * switch to plain (one-turn) grading.
 *
 * Kept in the same DataStore file as [FallbackSetting] (one DataStore per file per process).
 */
class GradingDebugSettings(
    private val prefs: DataStore<Preferences>,
    private val debugBuild: Boolean
) {
    val model: Flow<ModelSpec> = prefs.data.map { resolveModel(debugBuild, it[MODEL]) }
    val trimCrops: Flow<Boolean> = prefs.data.map { debugBuild && it[TRIM] == true }
    /** True unless a debug build chose plain grading. */
    val twoTurn: Flow<Boolean> = prefs.data.map { !(debugBuild && it[PLAIN] == true) }
    val forceRemark: Flow<Boolean> = prefs.data.map { debugBuild && it[FORCE_REMARK] == true }

    suspend fun currentModel(): ModelSpec = model.first()
    suspend fun currentTrimCrops(): Boolean = trimCrops.first()
    suspend fun currentTwoTurn(): Boolean = twoTurn.first()
    suspend fun currentForceRemark(): Boolean = forceRemark.first()

    /** Debug builds only; ignored otherwise. */
    suspend fun setModel(spec: ModelSpec) {
        if (!debugBuild) return
        prefs.edit { it[MODEL] = spec.name }
    }

    /** Debug builds only; ignored otherwise. */
    suspend fun setTrimCrops(value: Boolean) {
        if (!debugBuild) return
        prefs.edit { it[TRIM] = value }
    }

    /** Debug builds only; ignored otherwise. True = plain (one-turn) grading. */
    suspend fun setPlainGrading(value: Boolean) {
        if (!debugBuild) return
        prefs.edit { it[PLAIN] = value }
    }

    /** Debug builds only; ignored otherwise. */
    suspend fun setForceRemark(value: Boolean) {
        if (!debugBuild) return
        prefs.edit { it[FORCE_REMARK] = value }
    }

    companion object {
        // A new key, not "two_turn_override": two-turn used to be opt-in, so an old saved
        // "off" must not turn into plain grading now that two-turn is the default.
        private val PLAIN = booleanPreferencesKey("plain_grading_override")
        private val FORCE_REMARK = booleanPreferencesKey("force_remark_override")
        private val MODEL = stringPreferencesKey("grading_model_override")
        private val TRIM = booleanPreferencesKey("trim_crops_override")

        /** A saved name that is not one of [ModelSpec.GRADING_CHOICES] falls back to the default. */
        fun resolveModel(debugBuild: Boolean, saved: String?): ModelSpec =
            if (!debugBuild) ModelSpec.DEFAULT
            else ModelSpec.GRADING_CHOICES.firstOrNull { it.name == saved } ?: ModelSpec.DEFAULT
    }
}
