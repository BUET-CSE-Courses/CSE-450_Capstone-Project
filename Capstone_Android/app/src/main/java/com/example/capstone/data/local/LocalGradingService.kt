package com.example.capstone.data.local

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.example.capstone.domain.grading.DecodeSettings
import com.example.capstone.domain.grading.GradingModel
import com.example.capstone.domain.grading.ModelReplies
import com.example.capstone.domain.grading.RunawayGuard
import com.example.capstone.util.ImagePrep
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The on-device [GradingModel]: one LiteRT-LM call per answer box, on the
 * shared engine held by [LocalModelProvider].
 *
 * It only runs the call. What to send, and what the reply means, is decided by
 * [com.example.capstone.domain.grading.BoxGrader], so the rules live in plain
 * Kotlin that the unit tests cover.
 *
 * - The system prompt goes in as a system turn
 *   ([ConversationConfig.systemInstruction]), as the server sends it.
 *   UNVERIFIED on device for Qwen2-VL: Phase 1 experiment C probes it.
 * - Images go first, then the text. The server's OpenAI-style providers put the
 *   text first; the images-first order is the one this app has always used on
 *   LiteRT-LM. UNVERIFIED which order Qwen2-VL prefers.
 * - More than one image per message is UNVERIFIED on device (Phase 1
 *   experiment B). `EngineConfig.maxNumImages` is still unset.
 * - Runaway replies (session 7: one clause repeated until the context ran out,
 *   274 s for one box) are bounded two ways: the reply is capped at
 *   [DecodeSettings.DEFAULT] (the reply reserve [TokenBudget] keeps free), and it is
 *   streamed so [RunawayGuard] can stop it. A repetition penalty was tried and
 *   removed: on Qwen2-VL it failed every call at the first decode step ("Logits
 *   dimensions must be [batch_size, 1, vocab_size]"). If that error comes back,
 *   the call is retried once with [DecodeSettings.PLAIN] ([DecodeSettings.retryAfter]).
 * - The phone's prompt puts SCORE and CONFIDENCE before FEEDBACK, so generation
 *   stops at the end of the first FEEDBACK line
 *   ([RunawayGuard.feedbackLineEnd]); nothing after it is used.
 */
class LocalGradingService(
    private val modelProvider: LocalModelProvider
) : GradingModel {

    override val modelId: String get() = modelProvider.spec.name
    override val supportsVision: Boolean get() = modelProvider.spec.supportsVision
    override val maxNumTokens: Int get() = modelProvider.spec.maxNumTokens
    override val imageTokens: Int get() = modelProvider.spec.imageTokens

    /** Numbers calls across boxes so the log shows which call is the second box. */
    private var callCounter = 0

    /**
     * What the last [completeWithFollowUp] sent (the prepared PNGs, exactly as handed to the
     * engine) and measured per turn. For the debug benchmark; null before the first call.
     */
    @Volatile
    var lastCall: CallRecord? = null
        private set

    /** Turns of the call in progress, gathered into [lastCall]. */
    private val turns = mutableListOf<TurnStats>()

    /** The last few finished calls, oldest first, numbered from 1 ([callsSince]). */
    private val log = ArrayDeque<CallRecord>()

    /** How many calls have finished. */
    @Volatile
    var callCount: Int = 0
        private set

    /**
     * Finished calls after the first [count] (two-turn grading makes two per box). Only the
     * last [LOG_SIZE] are kept.
     */
    fun callsSince(count: Int): List<CallRecord> = synchronized(log) {
        val wanted = (callCount - count).coerceIn(0, log.size)
        log.toList().takeLast(wanted)
    }

    /**
     * One inference round trip in a FRESH conversation, so no context bleeds
     * between boxes. The conversation is closed even on failure. Throws on any
     * failure; the caller turns that into a fallback box.
     */
    override suspend fun complete(
        systemPrompt: String,
        userText: String,
        images: List<ByteArray>
    ): String = completeWithFollowUp(systemPrompt, userText, images) { null }.first

    /**
     * [complete], then at most one more turn **in the same conversation** (the model still
     * has the images and the question) when [followUp] asks for one after the first reply.
     */
    override suspend fun completeWithFollowUp(
        systemPrompt: String,
        userText: String,
        images: List<ByteArray>,
        followUp: (String) -> String?
    ): ModelReplies {
        // EXIF-corrected and scaled to ImagePrep.MAX_LONG_EDGE, as before.
        val prepared = images.mapIndexed { i, bytes ->
            ImagePrep.toGradingPng(bytes) ?: error("Image ${i + 1} of ${images.size} could not be decoded")
        }
        return modelProvider.withInference { engine ->
            synchronized(turns) { turns.clear() }
            lastCall = CallRecord(modelProvider.spec.name, prepared, emptyList())
            var settings = DecodeSettings.DEFAULT
            var replies: ModelReplies? = null
            while (replies == null) {
                replies = try {
                    attempt(engine, systemPrompt, userText, prepared, settings, followUp)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val next = DecodeSettings.retryAfter(t, settings) ?: throw t
                    Log.w(TAG, "retrying once with $next after ${t.message}")
                    settings = next
                    null
                }
            }
            val record = CallRecord(modelProvider.spec.name, prepared, synchronized(turns) { turns.toList() })
            lastCall = record
            synchronized(log) {
                log.addLast(record)
                while (log.size > LOG_SIZE) log.removeFirst()
                callCount++
            }
            replies
        }
    }

    /** One conversation with [settings]: the call, and the follow-up turn if asked for. Throws on failure. */
    private suspend fun attempt(
        engine: Engine,
        systemPrompt: String,
        userText: String,
        prepared: List<ByteArray>,
        settings: DecodeSettings,
        followUp: (String) -> String?
    ): ModelReplies {
        val callNo = ++callCounter
        val startedAt = SystemClock.elapsedRealtime()
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(Content.Text(systemPrompt)),
            // Deterministic grading, not creative writing.
            samplerConfig = SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE)
        )
        Log.i(
            TAG,
            "call#$callNo start model=${modelProvider.spec.name} images=${prepared.size} " +
                "png=${prepared.joinToString { "${it.size}B/${pngDimensions(it)}" }} " +
                "systemChars=${systemPrompt.length} userChars=${userText.length} " +
                "maxNumTokens=${modelProvider.spec.maxNumTokens} $settings"
        )
        val conversation = engine.createConversation(conversationConfig)
        Log.i(TAG, "call#$callNo conversation opened alive=${conversation.isAlive}")
        return try {
            val parts = prepared.map { Content.ImageBytes(it) } + Content.Text(userText)
            val first = turn(conversation, Contents.of(parts), settings, "call#$callNo", startedAt)
            val second = followUp(first)?.let { ask ->
                Log.i(TAG, "call#$callNo no readable mark; asking once more in the same conversation")
                turn(
                    conversation, Contents.of(Content.Text(ask)), settings, "call#$callNo repair",
                    SystemClock.elapsedRealtime()
                )
            }
            ModelReplies(first, second)
        } finally {
            conversation.close()
            Log.i(TAG, "call#$callNo conversation closed alive=${conversation.isAlive}")
        }
    }

    /** One message and its streamed reply, stopped early by [RunawayGuard]. Throws on failure. */
    private suspend fun turn(
        conversation: Conversation,
        contents: Contents,
        settings: DecodeSettings,
        label: String,
        startedAt: Long
    ): String {
        val streamed = StringBuilder()
        // Where the phone stopped the model: a loop (cut, marked) or a finished reply.
        // Written on LiteRT-LM's callback thread, read here after `finished` or `stop`.
        var cutAt: Int? = null
        var stoppedAt: Int? = null
        val finished = CompletableDeferred<Throwable?>()
        val stop = CompletableDeferred<Unit>()
        val raw = try {
            // The callback form, not the Flow one: 0.16.1's Flow calls
            // SendChannel.close$default, which this app's kotlinx-coroutines does not
            // have, and the NoSuchMethodError in onDone killed the process after every
            // reply (session 7).
            conversation.sendMessageAsync(
                contents,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (stop.isCompleted) return
                        synchronized(streamed) {
                            RunawayGuard.appendStreamed(streamed, message.textOrEmpty())
                            val loop = RunawayGuard.cutLength(streamed)
                            val done = if (loop == null) RunawayGuard.feedbackLineEnd(streamed) else null
                            if (loop != null) {
                                cutAt = loop
                                Log.w(TAG, "$label reply is looping at ${streamed.length} chars; cancelling")
                            } else if (done != null) {
                                stoppedAt = done
                                Log.i(TAG, "$label first FEEDBACK line complete at $done chars; stopping")
                            }
                            if (loop != null || done != null) {
                                stop.complete(Unit)
                                runCatching { conversation.cancelProcess() }
                            }
                        }
                    }

                    override fun onDone() {
                        finished.complete(null)
                    }

                    override fun onError(throwable: Throwable) {
                        finished.complete(throwable)
                    }
                },
                maxOutputToken = settings.maxOutputTokens
            )
            val failure = try {
                select<Throwable?> {
                    finished.onAwait { it }
                    // Stopped by the phone: give the engine a moment to wind down
                    // (onDone or onError), but never wait on it forever.
                    stop.onAwait { withTimeoutOrNull(STOP_GRACE_MS) { finished.await() } }
                }
            } catch (ce: CancellationException) {
                runCatching { conversation.cancelProcess() }
                throw ce
            }
            val phoneStopped = synchronized(streamed) { cutAt != null || stoppedAt != null }
            if (failure != null) {
                // Ending with an error is what a cancelled generation may do.
                if (!phoneStopped) throw failure
                Log.i(TAG, "$label ended after cancel: ${failure.javaClass.simpleName}: ${failure.message}")
            }
            synchronized(streamed) {
                cutAt?.let { streamed.substring(0, it) + RunawayGuard.CUT_MARKER }
                    ?: stoppedAt?.let { streamed.substring(0, it) }
                    ?: streamed.toString()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "$label sendMessage FAILED after ${SystemClock.elapsedRealtime() - startedAt} ms " +
                    "${t.javaClass.name}: ${t.message} ${conversation.statsLine()}",
                t
            )
            throw t
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        Log.i(TAG, "$label elapsedMs=$elapsedMs ${conversation.statsLine()}")
        synchronized(turns) { turns += conversation.turnStats(label, elapsedMs) }
        // Before any parsing or trimming.
        logVerbatim(TAG, "$label rawResponse", raw)
        return raw
    }

    /** "WxH" from the PNG header, for the log. Decodes bounds only. */
    private fun pngDimensions(png: ByteArray): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, opts)
        return "${opts.outWidth}x${opts.outHeight}"
    }

    /** One call as [lastCall] records it. [images] are the PNGs the engine was given. */
    class CallRecord(val modelId: String, val images: List<ByteArray>, val turns: List<TurnStats>)

    private companion object {
        const val TAG = "LocalGradingService"

        /** Calls kept for [callsSince]: enough for a two-turn box and then some. */
        const val LOG_SIZE = 8
        const val TEMPERATURE = 0.1
        const val TOP_K = 5
        const val TOP_P = 0.95

        /** How long a reply the phone stopped may take to end before the call goes on without it. */
        const val STOP_GRACE_MS = 10_000L

    }
}
