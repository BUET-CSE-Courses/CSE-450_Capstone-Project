package com.example.capstone.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Whether the app asks the server to re-mark low-confidence boxes (plan decision 4).
 *
 * The default is the flavor's `BuildConfig.FALLBACK_ENABLED` (local false, deployed true).
 * A debug build may override it from the courses screen; a release build ignores any
 * saved override. The server re-marks only when this is on **and** it has
 * SELF_HOSTED_LLM_URL.
 */
class FallbackSetting(
    private val prefs: DataStore<Preferences>,
    private val buildDefault: Boolean,
    private val debugBuild: Boolean
) {
    val enabled: Flow<Boolean> = prefs.data.map { resolve(buildDefault, debugBuild, it[OVERRIDE]) }

    suspend fun current(): Boolean = enabled.first()

    /** Debug builds only; ignored otherwise. */
    suspend fun setOverride(value: Boolean) {
        if (!debugBuild) return
        prefs.edit { it[OVERRIDE] = value }
    }

    companion object {
        const val FILE_NAME = "grading_settings"
        private val OVERRIDE = booleanPreferencesKey("fallback_enabled_override")

        fun resolve(buildDefault: Boolean, debugBuild: Boolean, override: Boolean?): Boolean =
            if (debugBuild && override != null) override else buildDefault
    }
}
