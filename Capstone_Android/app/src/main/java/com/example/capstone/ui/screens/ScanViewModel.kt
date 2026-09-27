package com.example.capstone.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.local.GradingRunStore
import com.example.capstone.data.local.PagePhotoStore
import com.example.capstone.data.remote.ExtractionResultDto
import com.example.capstone.data.remote.PageExtractionResultDto
import com.example.capstone.data.remote.userMessage
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.data.repository.SubmissionRepository
import com.example.capstone.data.repository.UploadImage
import com.example.capstone.domain.worksheet.MarkerCorners
import com.example.capstone.domain.worksheet.PageChecks
import com.example.capstone.domain.worksheet.PagePiece
import com.example.capstone.domain.worksheet.ServerVerdict
import com.example.capstone.extractor.AnswerCrop
import com.example.capstone.extractor.ExtractionResult
import com.example.capstone.extractor.Layout
import com.example.capstone.extractor.OpenCvNative
import com.example.capstone.extractor.PageExtractor
import com.example.capstone.util.ImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import kotlin.math.max

/** One printed page of the paper, as the scan screen shows it. */
data class PageRow(
    val pageIndex: Int,
    /** The (box, part) pieces printed on this page. Empty: nothing to photograph. */
    val expected: List<PagePiece>,
    /** What the web end holds for this page, or null when nothing is uploaded. */
    val server: PageExtractionResultDto?,
    /** The web end's page judged against the pack; null when [server] is null. */
    val verdict: ServerVerdict?,
    /** How many crops this phone holds for the page, or null for none. */
    val phoneCrops: Int?,
    /** Why the last photo of this page could not be used; shown with a retake. */
    val problem: String? = null,
    val problemDetail: String? = null
) {
    val hasAnswers: Boolean get() = expected.isNotEmpty()

    /** On the server, accepted there, and cropped on this phone. */
    val ready: Boolean get() = verdict is ServerVerdict.Accepted && phoneCrops != null
}

/** The most recent photo, with the boxes the phone read off it. */
data class PagePreview(
    val pageIndex: Int,
    val bitmap: Bitmap,
    /** Maps full-size photo pixels onto [bitmap]. */
    val scale: Float,
    val crops: List<AnswerCrop>
)

sealed interface ScanUiState {
    data object Loading : ScanUiState

    /** Nothing on this screen can work for this paper. Another photo will not help. */
    data class Blocked(val message: String) : ScanUiState

    data class Ready(
        val title: String,
        val pages: List<PageRow>,
        val submissionId: String?,
        val handedIn: Boolean,
        /** The page being processed and what is happening to it, or null when idle. */
        val busyPage: Int? = null,
        val busyLabel: String? = null,
        val handingIn: Boolean = false,
        /** A problem not tied to one page: loading the server's copy, handing in. */
        val message: String? = null,
        val preview: PagePreview? = null
    ) : ScanUiState {
        val busy: Boolean get() = busyPage != null || handingIn

        /** Pages that need a photo: every page with at least one answer box on it. */
        val requiredPages: List<Int> get() = pages.filter { it.hasAnswers }.map { it.pageIndex }

        /** Why hand-in is not possible yet, or null. */
        val handInProblem: String?
            get() = PageChecks.handInProblem(requiredPages, pages.filter { it.ready }.map { it.pageIndex }.toSet())
    }
}

/**
 * Photograph each printed page, upload it, and hand the script in.
 *
 * For each page, in this order:
 * 1. **Crop on the phone.** `:extractor` registers the photo on the pack's markers and cuts
 *    that page's segments. These crops are what the phone grades (plan decision 9). If the
 *    corners are not all found, the student retakes before anything is uploaded.
 * 2. **Upload** to `POST /api/submissions` with the page number the student picked, sent as
 *    `page_index` (see [SubmissionRepository.uploadPage]).
 * 3. **Judge the server's result** against the pack ([PageChecks.serverVerdict]). The server
 *    crops the page for the teacher and for fallback; if it could not, the student retakes.
 *    Its crops are never downloaded.
 * 4. Only then are the phone's crops saved ([PagePhotoStore]), so the crops held here always
 *    come from the photo the web end accepted.
 *
 * Hand-in (`POST /api/submissions/{id}/submit`) is offered only when every page with answer
 * boxes is ready, and the screen asks first: after it the answers cannot change.
 */
class ScanViewModel(
    private val context: Context,
    private val assignmentRepository: AssignmentRepository,
    private val submissionRepository: SubmissionRepository,
    private val pagePhotoStore: PagePhotoStore,
    private val runStore: GradingRunStore,
    private val baseUrl: String,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** The web end's question id (the paper); the pack is cached under it. */
    val assignmentId: String = checkNotNull(savedStateHandle.get<String>("assignmentId"))

    /**
     * Set to the submission id by a successful hand-in, so the screen can go straight on to
     * grading; cleared by [onHandInShown]. A paper that was already handed in does not set it.
     */
    var justHandedIn: String? by mutableStateOf(null)
        private set

    fun onHandInShown() {
        justHandedIn = null
    }

    var uiState: ScanUiState by mutableStateOf(ScanUiState.Loading)
        private set

    private var layout: Layout? = null
    private var courseId: String? = null

    init {
        load()
    }

    /** Reads the cached pack, this phone's crops, and the server's copy of the script. */
    fun load() {
        viewModelScope.launch {
            uiState = ScanUiState.Loading
            val cached = withContext(Dispatchers.IO) { assignmentRepository.cachedPack(assignmentId) }
            val assignment = withContext(Dispatchers.IO) { assignmentRepository.worksheetFor(assignmentId) }.getOrNull()
            if (cached == null || assignment == null) {
                uiState = ScanUiState.Blocked("Download this assignment first (open it from the list).")
                return@launch
            }
            val loaded = assignment.layout
                ?: run {
                    uiState = ScanUiState.Blocked(
                        "This paper has no printed page layout, so a photo of it cannot be matched to its answer boxes."
                    )
                    return@launch
                }
            if (loaded.answerBoxes.isEmpty()) {
                uiState = ScanUiState.Blocked("This paper has no answer boxes to photograph.")
                return@launch
            }
            layout = loaded
            courseId = cached.pack.courseId

            val lastWithAnswers = loaded.pagesWithAnswers.maxOrNull() ?: 0
            val pageCount = max(assignment.pageCount ?: 0, lastWithAnswers + 1)
            val phonePages = withContext(Dispatchers.IO) {
                (0 until pageCount).associateWith { pagePhotoStore.page(assignmentId, it)?.size }
            }
            val rows = (0 until pageCount).map { page ->
                PageRow(
                    pageIndex = page,
                    expected = PageChecks.expectedPieces(loaded, page),
                    server = null,
                    verdict = null,
                    phoneCrops = phonePages[page]
                )
            }
            uiState = ScanUiState.Ready(title = assignment.title, pages = rows, submissionId = null, handedIn = false)
            refreshServer()
            savedStateHandle.remove<Int>(CAPTURED_PAGE)?.let { processCapture(it) }
        }
    }

    /** Re-reads the server's copy: the submission, whether it is handed in, its pages. */
    fun refreshServer() {
        val state = uiState as? ScanUiState.Ready ?: return
        val course = courseId ?: return
        viewModelScope.launch {
            uiState = state.copy(busyPage = null, busyLabel = null, message = null)
            // Before the server is asked: a page saved after this (an upload finishing
            // meanwhile) is then never taken for a stale one.
            val phonePages = withContext(Dispatchers.IO) { pagePhotoStore.pages(assignmentId).toSet() }
            val mine = submissionRepository.myAssignment(course, assignmentId)
            val row = mine.getOrElse { e ->
                update { it.copy(message = "Couldn't read your script from the web end. ${e.userMessage(baseUrl)}") }
                return@launch
            }
            val submissionId = row?.submissionId
            val handedIn = row?.handedIn == true
            if (submissionId == null) {
                forgetStale(phonePages, submissionId = null, serverPages = null)
                update { it.copy(submissionId = null, handedIn = handedIn) }
                return@launch
            }
            submissionRepository.manifest(submissionId).fold(
                onSuccess = { manifest ->
                    forgetStale(phonePages, submissionId, manifest.pages.map { it.pageIndex }.toSet())
                    update { applyManifest(it.copy(submissionId = submissionId, handedIn = handedIn), manifest) }
                },
                onFailure = { e ->
                    update {
                        it.copy(
                            submissionId = submissionId,
                            handedIn = handedIn,
                            message = "Couldn't read your uploaded pages. ${e.userMessage(baseUrl)}"
                        )
                    }
                }
            )
        }
    }

    /**
     * After the server's copy is read: drops this phone's crops for pages the server does not
     * hold ([PageChecks.stalePhonePages]) and every saved grading run for this paper but the
     * current submission's. So a paper whose submission the teacher deleted starts fresh.
     */
    private suspend fun forgetStale(phonePages: Set<Int>, submissionId: String?, serverPages: Set<Int>?) {
        val stale = PageChecks.stalePhonePages(phonePages, serverPages)
        if (stale.isNotEmpty()) {
            Log.i(TAG, "dropping phone crops of pages $stale: the web end doesn't hold them")
            withContext(Dispatchers.IO) { stale.forEach { pagePhotoStore.removePage(assignmentId, it) } }
            update { st -> st.copy(pages = st.pages.map { if (it.pageIndex in stale) it.copy(phoneCrops = null) else it }) }
        }
        val forgotten = runStore.forgetOtherRuns(assignmentId, keepSubmissionId = submissionId)
        if (forgotten.isNotEmpty()) Log.i(TAG, "forgot saved grading runs $forgotten for this paper")
    }

    // ---- taking a photo ------------------------------------------------------------------

    /**
     * A file for the system camera to write page [pageIndex] into, as a content URI
     * (`ActivityResultContracts.TakePicture`). The page is remembered across process death,
     * which is when the camera is most likely to come back to a fresh process.
     */
    fun prepareCapture(pageIndex: Int): Uri {
        savedStateHandle[PENDING_PAGE] = pageIndex
        val file = captureFile().apply {
            parentFile?.mkdirs()
            delete()
        }
        return FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
    }

    /**
     * The camera finished. [saved] is false when the student backed out.
     *
     * If the app was killed while the camera was open, this arrives before [load] has
     * finished; the page is parked and processed once it has.
     */
    fun onCaptured(saved: Boolean) {
        val page = savedStateHandle.remove<Int>(PENDING_PAGE) ?: return
        val file = captureFile()
        if (!saved || !file.isFile || file.length() == 0L) {
            file.delete()
            return
        }
        if (uiState is ScanUiState.Ready) processCapture(page) else savedStateHandle[CAPTURED_PAGE] = page
    }

    private fun processCapture(page: Int) {
        val file = captureFile()
        processPage(page) {
            try {
                file.takeIf { it.isFile }?.readBytes()
            } finally {
                file.delete()
            }
        }
    }

    /** The student is about to choose page [pageIndex] from the gallery. */
    fun preparePick(pageIndex: Int) {
        savedStateHandle[PENDING_PAGE] = pageIndex
    }

    /** The gallery returned [uri], or null when the student backed out. */
    fun onPicked(uri: Uri?) {
        val page = savedStateHandle.remove<Int>(PENDING_PAGE) ?: return
        if (uri == null) return
        processPage(page) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
    }

    /** Takes page [pageIndex] off the server (before hand-in) and forgets this phone's crops of it. */
    fun removePage(pageIndex: Int) {
        val state = uiState as? ScanUiState.Ready ?: return
        if (state.busy || state.handedIn) return
        viewModelScope.launch {
            update { it.copy(busyPage = pageIndex, busyLabel = "Removing page ${pageIndex + 1}…", message = null) }
            val submissionId = state.submissionId
            if (submissionId != null && state.pages[pageIndex].server != null) {
                val result = submissionRepository.deletePage(submissionId, pageIndex)
                val manifest = result.getOrElse { e ->
                    update { it.copy(busyPage = null, busyLabel = null, message = "Couldn't remove page ${pageIndex + 1}. ${e.userMessage(baseUrl)}") }
                    return@launch
                }
                update { applyManifest(it, manifest) }
            }
            withContext(Dispatchers.IO) { pagePhotoStore.removePage(assignmentId, pageIndex) }
            update { st -> st.withPage(pageIndex) { it.copy(phoneCrops = null, problem = null, problemDetail = null) }.copy(busyPage = null, busyLabel = null) }
        }
    }

    // ---- hand-in -------------------------------------------------------------------------

    /** `POST /api/submissions/{id}/submit`. The screen has already asked the student. */
    fun handIn() {
        val state = uiState as? ScanUiState.Ready ?: return
        val submissionId = state.submissionId ?: return
        if (state.busy || state.handedIn || state.handInProblem != null) return
        viewModelScope.launch {
            update { it.copy(handingIn = true, message = null) }
            submissionRepository.handIn(submissionId).fold(
                onSuccess = {
                    update { it.copy(handingIn = false, handedIn = true) }
                    justHandedIn = submissionId
                },
                onFailure = { e ->
                    update { it.copy(handingIn = false, message = "Couldn't hand in. ${e.userMessage(baseUrl)}") }
                    // A 409 can mean it was handed in already (from another device); re-read.
                    if (e is HttpException && e.code() == 409) refreshServer()
                }
            )
        }
    }

    // ---- one page ------------------------------------------------------------------------

    private fun processPage(pageIndex: Int, read: () -> ByteArray?) {
        val state = uiState as? ScanUiState.Ready ?: return
        val layout = layout ?: return
        if (state.busy || state.handedIn || pageIndex !in state.pages.indices) return

        viewModelScope.launch {
            fun busy(label: String) = update { it.copy(busyPage = pageIndex, busyLabel = label, message = null) }
            fun fail(problem: String, detail: String?) = update { st ->
                st.withPage(pageIndex) { it.copy(problem = problem, problemDetail = detail) }
                    .copy(busyPage = null, busyLabel = null)
            }

            busy("Reading page ${pageIndex + 1}…")
            val cut = withContext(Dispatchers.IO) { cutOnPhone(layout, pageIndex, read) }
            val crops = when (cut) {
                is PhoneCut.Retake -> return@launch fail(cut.problem, cut.detail)
                is PhoneCut.Blocked -> {
                    uiState = ScanUiState.Blocked(cut.message)
                    return@launch
                }
                is PhoneCut.Cut -> cut
            }
            update { it.copy(preview = crops.preview) }

            busy("Uploading page ${pageIndex + 1}…")
            val uploaded = submissionRepository.uploadPage(
                questionId = assignmentId,
                pageIndex = pageIndex,
                submissionId = (uiState as? ScanUiState.Ready)?.submissionId,
                image = crops.upload
            )
            val manifest = uploaded.getOrElse { e ->
                // A refused upload changes nothing on the server (the route raises before it
                // stores the page), so any crops held for an earlier photo still match.
                if (e is HttpException && e.code() == 409) refreshServer()
                return@launch fail("The web end didn't take this photo.", e.userMessage(baseUrl))
            }

            val expected = PageChecks.expectedPieces(layout, pageIndex)
            val page = manifest.pages.firstOrNull { it.pageIndex == pageIndex }
            val verdict = page?.let { PageChecks.serverVerdict(expected, it) }
            if (verdict is ServerVerdict.Accepted) {
                withContext(Dispatchers.IO) { pagePhotoStore.savePage(assignmentId, pageIndex, crops.crops) }
                update { st ->
                    applyManifest(st, manifest)
                        .withPage(pageIndex) { it.copy(phoneCrops = crops.crops.size, problem = null, problemDetail = null) }
                        .copy(busyPage = null, busyLabel = null)
                }
            } else {
                // The server now holds this photo for the page, and could not use it. Crops of
                // an earlier photo no longer match what is uploaded, so they go too.
                withContext(Dispatchers.IO) { pagePhotoStore.removePage(assignmentId, pageIndex) }
                update { st ->
                    applyManifest(st, manifest)
                        .withPage(pageIndex) {
                            it.copy(
                                phoneCrops = null,
                                problem = "The web end couldn't read this page. Take it again.",
                                problemDetail = (verdict as? ServerVerdict.Refused)?.reason
                                    ?: "The server's reply has no page ${pageIndex + 1}."
                            )
                        }
                        .copy(busyPage = null, busyLabel = null)
                }
            }
        }
    }

    private sealed interface PhoneCut {
        class Cut(val crops: List<AnswerCrop>, val upload: UploadImage, val preview: PagePreview?) : PhoneCut
        class Retake(val problem: String, val detail: String?) : PhoneCut
        class Blocked(val message: String) : PhoneCut
    }

    /**
     * Registers the photo and cuts this page's segments, then prepares the upload.
     *
     * Holds the full-resolution PNG for as short a time as it can: a 12 MP photo is tens of
     * megabytes as PNG, and the decoded Mat inside the extractor is tens more.
     */
    private fun cutOnPhone(layout: Layout, pageIndex: Int, read: () -> ByteArray?): PhoneCut {
        val source = try {
            read()
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the photo", t)
            null
        } ?: return PhoneCut.Retake("That photo could not be opened.", "Take it again, or choose a different one.")

        // Orientation only. Downscaling would throw away the marker detail registration
        // depends on and the stroke detail the crops are for.
        val png = ImagePrep.toRegistrationPng(source)
            ?: return PhoneCut.Retake("That file is not an image this app can read.", "${source.size} bytes.")

        val result = try {
            OpenCvNative.load()
            PageExtractor().extractPage(layout, pageIndex, png)
        } catch (t: Throwable) {
            Log.e(TAG, "extraction failed", t)
            return PhoneCut.Retake("Something went wrong reading that photo.", "${t.javaClass.simpleName}: ${t.message}")
        }

        val crops = when (result) {
            is ExtractionResult.Success -> result.crops
            is ExtractionResult.MarkersNotFound -> return PhoneCut.Retake(
                MarkerCorners.sentence(layout, result.missingIds),
                "${result.found} of ${layout.markers.size} corner markers were found. Take the photo " +
                    "again with the whole sheet, all four corners, inside the frame."
            )
            is ExtractionResult.RegistrationFailed -> return PhoneCut.Retake(
                "The corners were found but the page could not be squared up.",
                result.reason
            )
            is ExtractionResult.Undecodable -> return PhoneCut.Retake(
                "That file is not an image this app can read.",
                result.cause.message ?: result.cause.javaClass.simpleName
            )
            // Not the student's photo: the geometry the server sent cannot be used.
            is ExtractionResult.InvalidLayout -> return PhoneCut.Blocked(
                "This paper's printed layout is not usable: ${result.reason}"
            )
        }

        val upload = ImagePrep.uploadMediaType(source)?.let { UploadImage(source, it) }
            ?: ImagePrep.toUploadJpeg(source)?.let { UploadImage(it, "image/jpeg") }
            ?: return PhoneCut.Retake("This photo could not be prepared for upload.", "Take it with the camera instead.")

        return PhoneCut.Cut(crops, upload, decodePreview(png)?.let { PagePreview(pageIndex, it.first, it.second, crops) })
    }

    /**
     * A display-sized copy of the photo, plus the factor that maps full-size image pixels
     * onto it - which is what the box overlay needs, because [AnswerCrop.imageQuad] is in
     * full-size pixels. `inSampleSize` only ever halves, so the real scale is recovered from
     * the decoded width rather than assumed.
     */
    private fun decodePreview(bytes: ByteArray): Pair<Bitmap, Float>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_LONG_EDGE) sample *= 2
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory decoding the preview", e)
            null
        } ?: return null
        return bitmap to bitmap.width.toFloat() / bounds.outWidth.toFloat()
    }

    // ---- state helpers ---------------------------------------------------------------------

    private fun update(change: (ScanUiState.Ready) -> ScanUiState.Ready) {
        val state = uiState as? ScanUiState.Ready ?: return
        uiState = change(state)
    }

    private fun ScanUiState.Ready.withPage(pageIndex: Int, change: (PageRow) -> PageRow) =
        copy(pages = pages.map { if (it.pageIndex == pageIndex) change(it) else it })

    /** The server's pages onto the rows. A page absent from [manifest] has nothing uploaded. */
    private fun applyManifest(state: ScanUiState.Ready, manifest: ExtractionResultDto): ScanUiState.Ready {
        val byPage = manifest.pages.associateBy { it.pageIndex }
        return state.copy(
            submissionId = manifest.submissionId,
            pages = state.pages.map { row ->
                val page = byPage[row.pageIndex]
                row.copy(server = page, verdict = page?.let { PageChecks.serverVerdict(row.expected, it) })
            }
        )
    }

    private fun captureFile() = File(File(context.cacheDir, CAPTURE_DIR), "capture.jpg")

    companion object {
        private const val TAG = "ScanViewModel"
        private const val PENDING_PAGE = "pendingPage"
        private const val CAPTURED_PAGE = "capturedPage"

        /** Must match res/xml/capture_paths.xml and the provider in AndroidManifest.xml. */
        private const val CAPTURE_DIR = "captures"
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

        /** Long edge of the on-screen copy of the photo. */
        private const val PREVIEW_LONG_EDGE = 1600

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as CapstoneApplication
                    )
                val container = application.container
                ScanViewModel(
                    context = application,
                    assignmentRepository = container.assignmentRepository,
                    submissionRepository = container.submissionRepository,
                    pagePhotoStore = container.pagePhotoStore,
                    runStore = container.gradingRunStore,
                    baseUrl = container.baseUrl,
                    savedStateHandle = this.createSavedStateHandle()
                )
            }
        }
    }
}
