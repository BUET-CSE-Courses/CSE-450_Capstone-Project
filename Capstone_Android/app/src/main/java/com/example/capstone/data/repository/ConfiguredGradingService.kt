package com.example.capstone.data.repository

import com.example.capstone.data.local.GradingDebugSettings
import com.example.capstone.data.local.ModelSpec
import com.example.capstone.domain.grading.AnswerToGrade
import com.example.capstone.domain.grading.BoxGradeResult
import com.example.capstone.domain.grading.GradingConfig
import com.example.capstone.domain.grading.GradingService

/**
 * The [GradingService] real grading runs use (`GradingWorker` → `OnDeviceGradingRunner`).
 * Before each box it reads [settings]: the model to make active and the grading config.
 * Defaults, and all a release build uses (session 9b): Gemma 4 E2B, two-turn, no trim.
 * The Model Test screen and the benchmark build their own graders and do not go through here.
 *
 * @param useModel makes a model active; a no-op when it already is.
 * @param grader the per-box grader for one config.
 */
class ConfiguredGradingService(
    private val settings: GradingDebugSettings,
    private val confidenceThreshold: Double,
    private val useModel: suspend (ModelSpec) -> Unit,
    private val grader: (GradingConfig) -> GradingService
) : GradingService {

    override suspend fun grade(item: AnswerToGrade): BoxGradeResult {
        useModel(settings.currentModel())
        return grader(currentConfig()).grade(item)
    }

    suspend fun currentConfig(): GradingConfig = GradingConfig(
        confidenceThreshold = confidenceThreshold,
        trimCrops = settings.currentTrimCrops(),
        twoTurn = settings.currentTwoTurn()
    )
}
