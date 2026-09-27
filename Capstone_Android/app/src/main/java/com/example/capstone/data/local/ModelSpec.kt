package com.example.capstone.data.local

import android.util.Log

/**
 * The on-device models the app knows how to run.
 *
 * One entry per model file that can be adb-pushed to
 * [LocalModelProvider.MODEL_DIR]. [LocalModelProvider] reads [fileName]
 * and [maxNumTokens] from the active spec instead of hardcoding them.
 *
 * [minRamGb] is advisory only - nothing enforces it. It records what the
 * model realistically needs so a device that cannot hold it is a known
 * limitation rather than a mystery OOM.
 */
enum class ModelSpec(
    /** Filename as pushed to [LocalModelProvider.MODEL_DIR]. */
    val fileName: String,
    /** Human-readable label for the debug screen. */
    val displayName: String,
    /** True when the model accepts [com.google.ai.edge.litertlm.Content.ImageBytes]. */
    val supportsVision: Boolean,
    /** Advisory floor on device RAM, in GB. Null when no figure is recorded. */
    val minRamGb: Int?,
    /** KV-cache size: input plus output tokens. */
    val maxNumTokens: Int,
    /**
     * Tokens one image consumes out of [maxNumTokens].
     *
     * Zero for text-only models, which never receive an image. For vision
     * models this is what the prompt and the response have to share
     * [maxNumTokens] with.
     */
    val imageTokens: Int,
    /**
     * Rough on-disk size in MB, for display in the model picker so the size of
     * a download or push is visible before it is selected. Approximate by
     * design - [expectedBytes] is the value anything may be checked against.
     */
    val approxSizeMb: Int,
    /**
     * Exact size of the published model file, in bytes, or null when no
     * published figure is recorded for this model.
     *
     * A short `adb push` (cable knocked out, no space left on device) leaves a
     * truncated but perfectly openable file. Native LiteRT-LM code tends to
     * abort on that, which kills the process instead of raising something
     * catchable, so the size is checked in Kotlin first. That exact check is
     * skipped for entries where this is null; only the non-empty check applies.
     */
    val expectedBytes: Long?
) {
    LLAVA_OV_05B(
        fileName = "LLaVA-OneVision-0.5B.litertlm",
        displayName = "LLaVA-OneVision 0.5B (multimodal, small)",
        supportsVision = true,
        minRamGb = 4,
        // This model spends 730 tokens per image, so the budget has to leave
        // room for the prompt and the graded response on top of that.
        maxNumTokens = 2048,
        imageTokens = 730,
        approxSizeMb = 791,
        expectedBytes = 829_262_144L
    ),

    /**
     * Larger multimodal model with a token budget roomy enough that one image
     * does not crowd out the prompt.
     *
     * No published byte count is recorded here, so [expectedBytes] is null and
     * the truncated-push check does not apply to this entry.
     */
    QWEN2_VL_2B(
        fileName = "Qwen2-VL-2B.litertlm",
        displayName = "Qwen2-VL 2B (multimodal, best OCR)",
        supportsVision = true,
        minRamGb = null,
        maxNumTokens = 4096,
        imageTokens = 576,
        approxSizeMb = 1780,
        expectedBytes = null
    ),

    /**
     * Gemma 4 E2B-it, the grading model ([DEFAULT], session 9b; added in session 9).
     *
     * From `litert-community/gemma-4-E2B-it-litert-lm` (Apache-2.0, not gated), file
     * `gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes per the Hugging Face API. Gemma 4 is
     * supported by LiteRT-LM since v0.10.1; this app is on 0.16.1.
     * - [imageTokens] 280: `max_soft_tokens` / `image_seq_length` in `google/gemma-4-E2B-it`'s
     *   `processor_config.json` (budgets 70..1120). What the `.litertlm` build uses is
     *   **UNVERIFIED**; the benchmark logs the real prompt token counts.
     * - [maxNumTokens] 4096, as Qwen: the model card allows up to 32k on LiteRT-LM and
     *   benchmarks at 2048. 4096 keeps the two models on the same budget.
     * - Backends: CPU for text, GPU for vision, as for Qwen ([LocalModelProvider]). The card
     *   benchmarks both CPU and GPU; which is faster on this phone is **UNVERIFIED**.
     * - [minRamGb]: the card gives 1.7 GB CPU memory (S26 Ultra, CPU backend), no floor.
     */
    GEMMA4_E2B(
        fileName = "gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B (multimodal)",
        supportsVision = true,
        minRamGb = null,
        maxNumTokens = 4096,
        imageTokens = 280,
        approxSizeMb = 2468,
        expectedBytes = 2_588_147_712L
    ),

    /**
     * Text-only fallback. It cannot read the worksheet photo, so it is useful
     * for isolating engine problems from vision problems - not for grading.
     */
    GEMMA3_1B(
        fileName = "gemma3-1b-it-int4.litertlm",
        displayName = "Gemma 3 1B (text only)",
        supportsVision = false,
        minRamGb = 6,
        maxNumTokens = 1024,
        // Text-only: no image is ever attached, so no image tokens are spent.
        imageTokens = 0,
        approxSizeMb = 557,
        expectedBytes = 584_417_280L
    );

    /**
     * True when this is a vision model whose token budget is tight enough that
     * image tokens could crowd out the prompt and the response.
     */
    val hasTightVisionBudget: Boolean
        get() = supportsVision && maxNumTokens < MIN_VISION_TOKENS

    companion object {
        private const val TAG = "ModelSpec"

        /**
         * Below this, a vision model's image tokens can consume most of the
         * budget. Advisory: it warns, it does not reject.
         */
        const val MIN_VISION_TOKENS = 1500

        /**
         * The spec used unless a caller picks another one. The user's decision (session 9b,
         * 2026-09-26): Gemma 4 E2B with two-turn grading, in local and deployed builds. Qwen2-VL
         * 2B stays in this enum for the Model Test screen only.
         */
        val DEFAULT = GEMMA4_E2B

        /** The models real grading may use ([GradingDebugSettings]). DEFAULT first. Qwen removed (session 9b). */
        val GRADING_CHOICES = listOf(GEMMA4_E2B)

        init {
            entries.filter { it.hasTightVisionBudget }.forEach { spec ->
                Log.w(
                    TAG,
                    "${spec.name} supports vision but maxNumTokens=${spec.maxNumTokens} " +
                        "is under $MIN_VISION_TOKENS. Image tokens alone can consume most " +
                        "of that budget, leaving little room for the prompt and response."
                )
            }
        }
    }
}
