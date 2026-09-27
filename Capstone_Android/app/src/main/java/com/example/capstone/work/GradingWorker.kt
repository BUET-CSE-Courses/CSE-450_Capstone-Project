package com.example.capstone.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.capstone.CapstoneApplication
import com.example.capstone.MainActivity
import com.example.capstone.data.local.RunPhase
import com.example.capstone.data.repository.RunOutcome
import com.example.capstone.data.repository.RunProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs one submission's on-device grading run
 * ([com.example.capstone.data.repository.OnDeviceGradingRunner]) outside any screen.
 *
 * Expedited, and promoted to a foreground service (type dataSync) with a
 * "Grading box 2 of 5" notification, so turning the screen off or leaving the app does not
 * stop it. If Android kills it anyway, WorkManager runs it again and the runner resumes
 * from the saved progress. Network failures that outlast the runner's own retries become
 * [Result.retry] (WorkManager's exponential backoff).
 */
class GradingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val submissionId = inputData.getString(KEY_SUBMISSION_ID) ?: return Result.failure()
        val questionId = inputData.getString(KEY_QUESTION_ID) ?: return Result.failure()
        val container = (applicationContext as CapstoneApplication).container

        promote(RunProgress(RunPhase.PREPARING, 0, 0, null))
        val outcome = container.gradingRunner.run(
            submissionId = submissionId,
            questionId = questionId,
            fallbackEnabled = container.fallbackSetting.current(),
            forceRemark = container.gradingDebugSettings.currentForceRemark(),
            prepare = { withContext(Dispatchers.IO) { container.paperPreparer.prepare(questionId) } },
            onProgress = { promote(it) }
        )
        Log.i(TAG, "run for $submissionId ended: ${outcome.javaClass.simpleName}")
        return when (outcome) {
            is RunOutcome.Posted, is RunOutcome.ServerHasMarks -> {
                notifyDone(applicationContext, "Your marks are ready", "Open the app to see them.")
                Result.success()
            }
            is RunOutcome.Blocked -> {
                notifyDone(applicationContext, "Grading stopped", outcome.message)
                Result.failure()
            }
            is RunOutcome.RetryLater ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** Needed for an expedited job on Android 11 and older. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext, RunProgress(RunPhase.PREPARING, 0, 0, null))

    private suspend fun promote(progress: RunProgress) {
        try {
            setForeground(foregroundInfo(applicationContext, progress))
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a foreground start from the background
            // (ForegroundServiceStartNotAllowedException). The expedited job still runs.
            Log.w(TAG, "could not show the grading notification: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GradingWorker"
        const val KEY_SUBMISSION_ID = "submission_id"
        const val KEY_QUESTION_ID = "question_id"
        private const val MAX_ATTEMPTS = 8

        private const val CHANNEL_ID = "grading"
        private const val PROGRESS_NOTIFICATION_ID = 4501
        private const val DONE_NOTIFICATION_ID = 4502

        fun uniqueName(submissionId: String) = "on-device-grading-$submissionId"

        /**
         * Starts the run now. A run already **running** for this submission is kept, so there
         * is one worker per submission and never two posts. One only waiting (a retry backoff)
         * is replaced, so opening the screen or tapping retry starts it at once: after four
         * crashes in session 7 the backoff had grown to minutes, KEEP left it waiting, and
         * WorkManager refuses a forced early run. Blocks briefly; call off the main thread.
         */
        fun enqueue(context: Context, submissionId: String, questionId: String) {
            val request = OneTimeWorkRequestBuilder<GradingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_SUBMISSION_ID to submissionId, KEY_QUESTION_ID to questionId))
                .build()
            val workManager = WorkManager.getInstance(context)
            val states = try {
                workManager.getWorkInfosForUniqueWork(uniqueName(submissionId)).get().map { it.state }
            } catch (e: Exception) {
                Log.w(TAG, "could not read the grading work's state", e)
                null
            }
            val policy = policyFor(states)
            Log.i(TAG, "enqueue $submissionId: existing=$states policy=$policy")
            workManager.enqueueUniqueWork(uniqueName(submissionId), policy, request)
        }

        /** KEEP a running (or unknown) run; REPLACE one that is only waiting, or finished. */
        fun policyFor(states: List<WorkInfo.State>?): ExistingWorkPolicy =
            if (states == null || WorkInfo.State.RUNNING in states) ExistingWorkPolicy.KEEP
            else ExistingWorkPolicy.REPLACE

        fun progressText(p: RunProgress): String = when (p.phase) {
            RunPhase.PREPARING -> "Getting your answers ready…"
            RunPhase.STARTING -> "Starting…"
            RunPhase.GRADING -> "Grading box ${p.current} of ${p.total}"
            RunPhase.POSTING -> "Sending your marks…"
            RunPhase.WAITING_FOR_NETWORK -> "Waiting for the connection…"
            RunPhase.POSTED, RunPhase.SERVER_HAS_MARKS -> "Marks are ready"
            RunPhase.BLOCKED -> "Grading stopped"
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Grading", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Grading your answers on this phone"
                    }
                )
            }
        }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun foregroundInfo(context: Context, progress: RunProgress): ForegroundInfo {
            ensureChannel(context)
            val grading = progress.phase == RunPhase.GRADING && progress.total > 0
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Grading your paper")
                .setContentText(progressText(progress))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(context))
                .setProgress(progress.total, if (grading) progress.current - 1 else 0, !grading)
                .build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
            }
        }

        private fun notifyDone(context: Context, title: String, text: String) {
            ensureChannel(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            try {
                NotificationManagerCompat.from(context).notify(DONE_NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "no permission to notify", e)
            }
        }
    }
}
