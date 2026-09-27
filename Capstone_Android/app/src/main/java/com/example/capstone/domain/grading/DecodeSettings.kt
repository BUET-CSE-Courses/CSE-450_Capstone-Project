package com.example.capstone.domain.grading

/**
 * The per-call decode settings the phone passes to LiteRT-LM, beyond the conversation's
 * sampler (top-k, top-p, temperature).
 *
 * Session 7 on device (Qwen2-VL 2B, LiteRT-LM 0.16.1): with a `RepetitionPenaltyConfig` and
 * `maxOutputToken` both set, every call failed at its first decode step, after the full
 * prefill, with "Status Code: 3. Message: Logits dimensions must be [batch_size, 1,
 * vocab_size]". The repetition penalty is the one that works on the logits, so it was
 * removed; the log could not tell the two apart, so the cap is still UNVERIFIED. If the error
 * comes back, the call is tried once more with [PLAIN]; [RunawayGuard] still bounds that reply.
 */
data class DecodeSettings(val maxOutputTokens: Int?) {
    companion object {
        /** The reply capped at the reserve [TokenBudget] keeps free for it. */
        val DEFAULT = DecodeSettings(maxOutputTokens = TokenBudget.DEFAULT_REPLY_RESERVE_TOKENS)

        /** Nothing but the conversation's own sampler. */
        val PLAIN = DecodeSettings(maxOutputTokens = null)

        const val LOGITS_SHAPE_ERROR = "Logits dimensions must be"

        /** The settings to try next after [failure] with [tried], or null to give up. */
        fun retryAfter(failure: Throwable, tried: DecodeSettings): DecodeSettings? =
            if (tried != PLAIN && failure.message?.contains(LOGITS_SHAPE_ERROR) == true) PLAIN else null
    }
}
