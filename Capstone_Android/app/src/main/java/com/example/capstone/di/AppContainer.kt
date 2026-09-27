package com.example.capstone.di

import android.content.Context
import com.example.capstone.BuildConfig
import com.example.capstone.data.auth.AuthInterceptor
import com.example.capstone.data.auth.MsalAuthManager
import com.example.capstone.data.auth.MsalConfig
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.capstone.data.local.FallbackSetting
import com.example.capstone.data.local.GradingDebugSettings
import com.example.capstone.data.local.GradingRunStore
import com.example.capstone.data.local.LocalGradingService
import com.example.capstone.data.local.LocalModelProvider
import com.example.capstone.data.local.PackStore
import com.example.capstone.data.local.PagePhotoStore
import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.data.repository.AuthRepository
import com.example.capstone.data.repository.ConfiguredGradingService
import com.example.capstone.data.repository.CourseRepository
import com.example.capstone.data.repository.OnDeviceGradingRunner
import com.example.capstone.data.repository.PaperPreparer
import com.example.capstone.data.repository.SubmissionRepository
import com.example.capstone.domain.grading.BoxGrader
import com.example.capstone.domain.grading.GradingConfig
import com.example.capstone.domain.grading.GradingService
import com.example.capstone.domain.grading.WorksheetGrader
import com.example.capstone.util.ImagePrep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

interface AppContainer {
    /** The web end this build talks to, ".../api/" (flavor `local` or `deployed`). */
    val baseUrl: String

    /** What local.properties is missing for sign-in, as actionable lines. Empty when configured. */
    val configProblems: List<String>

    val authRepository: AuthRepository
    val courseRepository: CourseRepository
    val assignmentRepository: AssignmentRepository

    /** Page uploads and hand-in (`routers/submissions.py`). */
    val submissionRepository: SubmissionRepository

    /**
     * Concrete provider, exposed only so the temporary ModelTestScreen can probe
     * the engine directly. Production code must not use this.
     */
    val localModelProvider: LocalModelProvider

    /** The on-device model call itself. Exposed for the debug benchmark only. */
    val localGradingModel: LocalGradingService

    /**
     * Per-box grading, as an interface: the web end's prompt and parser, blank
     * detection and the token budget, on top of the on-device model. Each box uses
     * the model and trim chosen in [gradingDebugSettings] (release: the defaults).
     */
    val gradingService: GradingService

    /** Debug builds: which model grades, crop trim, two-turn grading, force server re-mark. */
    val gradingDebugSettings: GradingDebugSettings

    /** Grades a whole worksheet, one answer box at a time, on top of [gradingService]. */
    val worksheetGrader: WorksheetGrader

    /**
     * The phone's own crops of each photographed page, on disk: written by the
     * scan screen, read by the grading screen, kept across process death.
     */
    val pagePhotoStore: PagePhotoStore

    /** Grading-run progress (DataStore), so a run resumes after the app is killed. */
    val gradingRunStore: GradingRunStore

    /** `BuildConfig.FALLBACK_ENABLED`, with the debug-build override. */
    val fallbackSetting: FallbackSetting

    /** Cached pack + the phone's crops, as grading items. */
    val paperPreparer: PaperPreparer

    /** Start, grade box by box, one results post (run by work/GradingWorker). */
    val gradingRunner: OnDeviceGradingRunner
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    // BASE_URL and MSAL_CLIENT_ID are set as a pair per flavor in app/build.gradle.kts.
    override val baseUrl: String = BuildConfig.BASE_URL

    override val configProblems: List<String> =
        MsalConfig.problems(BuildConfig.FLAVOR, BuildConfig.MSAL_CLIENT_ID, BuildConfig.MSAL_SIGNATURE_HASH, baseUrl)

    private val msalAuthManager: MsalAuthManager by lazy {
        MsalAuthManager(context, BuildConfig.MSAL_CLIENT_ID, BuildConfig.MSAL_SIGNATURE_HASH)
    }

    // HEADERS, never BODY: the pack is the answer key. Authorization is redacted
    // so bearer tokens never reach logcat.
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
        redactHeader("Authorization")
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            // Auth first, so the log line shows the request as sent (redacted).
            .addInterceptor(AuthInterceptor(msalAuthManager))
            .addInterceptor(loggingInterceptor)
            // OkHttp defaults to 10s on each of these. Connect stays short so a
            // missing `adb reverse` fails fast and obviously; read and write are
            // longer because worksheet photo uploads are large over USB.
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val retrofitService: ApiService by lazy {
        Retrofit.Builder()
            // A deployed build without webend.deployedUrl still starts; sign-in
            // is refused with configProblems before any call is made.
            .baseUrl(baseUrl.ifBlank { UNCONFIGURED_BASE_URL })
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    override val authRepository: AuthRepository by lazy {
        AuthRepository(msalAuthManager, retrofitService)
    }

    override val courseRepository: CourseRepository by lazy {
        CourseRepository(retrofitService)
    }

    override val assignmentRepository: AssignmentRepository by lazy {
        AssignmentRepository(
            retrofitService,
            // noBackupFilesDir: packs carry the answer key.
            PackStore(File(context.noBackupFilesDir, "packs"))
        )
    }

    override val submissionRepository: SubmissionRepository by lazy {
        SubmissionRepository(retrofitService)
    }

    override val localModelProvider: LocalModelProvider by lazy {
        LocalModelProvider(context)
    }

    override val localGradingModel: LocalGradingService by lazy {
        LocalGradingService(localModelProvider)
    }

    // Real grading: the settings' model and config (default Gemma 4 E2B, two-turn) per box.
    override val gradingService: GradingService by lazy {
        ConfiguredGradingService(
            settings = gradingDebugSettings,
            confidenceThreshold = CONFIDENCE_THRESHOLD,
            useModel = localModelProvider::useSpec,
            grader = { config ->
                BoxGrader(
                    model = localGradingModel,
                    decodeGray = ImagePrep::toGray,
                    config = config,
                    cropTo = ImagePrep::trimmedPng
                )
            }
        )
    }

    override val worksheetGrader: WorksheetGrader by lazy {
        WorksheetGrader(gradingService)
    }

    // One instance for the process: its methods synchronise on it.
    override val pagePhotoStore: PagePhotoStore by lazy {
        PagePhotoStore(File(context.noBackupFilesDir, "scans"))
    }

    // DataStore needs one instance per file for the whole process, and a scope that
    // outlives any screen.
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val gradingRunStore: GradingRunStore by lazy {
        // noBackupFilesDir: the phone's raw replies can quote the answer key.
        GradingRunStore.create(File(context.noBackupFilesDir, GradingRunStore.FILE_NAME), storeScope)
    }

    // One DataStore for the file, shared by the settings kept in it.
    private val gradingPrefs by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            context.preferencesDataStoreFile(FallbackSetting.FILE_NAME)
        }
    }

    override val fallbackSetting: FallbackSetting by lazy {
        FallbackSetting(
            gradingPrefs,
            buildDefault = BuildConfig.FALLBACK_ENABLED,
            debugBuild = BuildConfig.DEBUG
        )
    }

    override val gradingDebugSettings: GradingDebugSettings by lazy {
        GradingDebugSettings(gradingPrefs, debugBuild = BuildConfig.DEBUG)
    }

    override val paperPreparer: PaperPreparer by lazy {
        PaperPreparer(assignmentRepository, pagePhotoStore)
    }

    override val gradingRunner: OnDeviceGradingRunner by lazy {
        OnDeviceGradingRunner(retrofitService, gradingRunStore, gradingService)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 60L
        const val WRITE_TIMEOUT_SECONDS = 60L

        const val UNCONFIGURED_BASE_URL = "http://webend-url-not-set.invalid/api/"

        /** Below this CONFIDENCE (0..100) a box goes to fallback. */
        const val CONFIDENCE_THRESHOLD = GradingConfig.DEFAULT_CONFIDENCE_THRESHOLD
    }
}
