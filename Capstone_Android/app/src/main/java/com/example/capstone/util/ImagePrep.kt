package com.example.capstone.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.capstone.domain.grading.GrayImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares a worksheet photo for the two things that consume one.
 *
 * [toGradingPng] is the inference path: decode -> apply EXIF orientation ->
 * downscale to at most [MAX_LONG_EDGE] on the long edge -> re-encode as PNG.
 *
 * [toRegistrationPng] is the extraction path: decode -> apply EXIF orientation
 * -> re-encode as PNG, at full resolution. Marker detection and crop sharpness
 * both need every pixel the camera produced.
 *
 * PNG rather than JPEG on purpose: JPEG ringing artifacts destroy thin pen
 * strokes, which is exactly the signal the model needs to read handwriting.
 */
object ImagePrep {

    /** Maximum length of the longer edge handed to the model. */
    const val MAX_LONG_EDGE = 1024

    private const val TAG = "ImagePrep"

    /** Quality of a photo re-encoded for upload; see [toUploadJpeg]. */
    private const val UPLOAD_JPEG_QUALITY = 95

    /**
     * "image/jpeg" or "image/png" when [source] starts like one, else null.
     *
     * The web end decodes uploads with `cv2.imdecode` (`services/extractor.py`), which reads
     * JPEG and PNG. A camera's JPEG goes up as it is: registration there is by marker id, so
     * it does not depend on the EXIF orientation being applied first.
     */
    fun uploadMediaType(source: ByteArray): String? = when {
        source.size >= 3 && source[0] == 0xFF.toByte() && source[1] == 0xD8.toByte() &&
            source[2] == 0xFF.toByte() -> "image/jpeg"
        source.size >= 8 && source[0] == 0x89.toByte() && source[1] == 'P'.code.toByte() &&
            source[2] == 'N'.code.toByte() && source[3] == 'G'.code.toByte() -> "image/png"
        else -> null
    }

    /**
     * [source] (HEIC, WebP, ... from the gallery) as an upright full-resolution JPEG, for a
     * photo the web end could not decode as it is. Null if it cannot be decoded.
     */
    fun toUploadJpeg(source: ByteArray): ByteArray? {
        if (source.isEmpty()) return null
        var working: Bitmap = try {
            BitmapFactory.decodeByteArray(source, 0, source.size)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory decoding image for upload", e)
            null
        } ?: return null
        return try {
            working = applyOrientation(working, exifMatrix(source))
            ByteArrayOutputStream().use { out ->
                if (!working.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, out)) return null
                out.toByteArray()
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory encoding image for upload", e)
            null
        } finally {
            working.recycle()
        }
    }

    /**
     * Converts arbitrary image [source] bytes (JPEG from camera or gallery, PNG,
     * WebP, ...) into a correctly oriented PNG no larger than [maxLongEdge] on
     * its long edge.
     *
     * @return the PNG bytes, or null if [source] is not a decodable image or
     *   memory ran out. Never throws for bad input.
     */
    fun toGradingPng(source: ByteArray, maxLongEdge: Int = MAX_LONG_EDGE): ByteArray? {
        if (source.isEmpty()) {
            Log.w(TAG, "empty input bytes")
            return null
        }

        // Pass 1: bounds only, so a 12MP photo never gets fully allocated.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "undecodable image data (w=${bounds.outWidth} h=${bounds.outHeight})")
            return null
        }

        // Pass 2: decode subsampled. max(w, h) is unchanged by rotation, so it is
        // safe to pick the sample size before the EXIF transform is applied.
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var working: Bitmap = try {
            BitmapFactory.decodeByteArray(source, 0, source.size, decodeOptions)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory decoding image", e)
            null
        } ?: run {
            Log.w(TAG, "decode returned no bitmap")
            return null
        }

        return try {
            // Orientation BEFORE resizing, so the resize sees the true edges.
            working = applyOrientation(working, exifMatrix(source))
            working = scaleToLongEdge(working, maxLongEdge)

            ByteArrayOutputStream().use { out ->
                // PNG is lossless; the quality argument is ignored.
                if (!working.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    Log.w(TAG, "PNG compress failed")
                    return null
                }
                val png = out.toByteArray()
                Log.i(
                    TAG,
                    "prepared PNG ${working.width}x${working.height} " +
                        "inBytes=${source.size} outBytes=${png.size} sampleSize=${decodeOptions.inSampleSize}"
                )
                png
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory preparing image", e)
            null
        } finally {
            // Intermediates are recycled inside the helpers; this frees the last one.
            working.recycle()
        }
    }

    /**
     * Decodes a crop to greyscale at full resolution for
     * [com.example.capstone.domain.grading.BlankDetector], using PIL's RGB to L
     * formula so the phone judges blankness on the same numbers as the server.
     * No EXIF transform and no scaling: the server reads the crop as it is.
     *
     * @return null if [source] is not a decodable image or memory ran out; the
     *   blank check then treats the crop as written on. Never throws for bad input.
     */
    /**
     * [source] as [plan] says, as PNG, for the model's input only
     * ([com.example.capstone.domain.grading.CropTrim]): cut to `plan.cut` (pixels of the
     * decoded image), then the printed label (`plan.paint`, pixels of the cut) filled with the
     * paper's tone. Null when it cannot be decoded or cut; the caller then sends the whole crop.
     */
    fun trimmedPng(source: ByteArray, plan: com.example.capstone.domain.grading.CropTrim.Plan): ByteArray? {
        val bitmap = try {
            BitmapFactory.decodeByteArray(source, 0, source.size)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory decoding crop to trim", e)
            null
        } ?: return null
        val rect = plan.cut
        return try {
            if (rect.left < 0 || rect.top < 0 || rect.width <= 0 || rect.height <= 0 ||
                rect.right > bitmap.width || rect.bottom > bitmap.height
            ) return null
            // A mutable copy of the cut, so the label can be painted over.
            val cut = Bitmap.createBitmap(rect.width, rect.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(cut)
                canvas.drawBitmap(
                    bitmap,
                    android.graphics.Rect(rect.left, rect.top, rect.right, rect.bottom),
                    android.graphics.Rect(0, 0, rect.width, rect.height),
                    null
                )
                plan.paint?.let { p ->
                    val tone = plan.paperTone.coerceIn(0, 255)
                    val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(tone, tone, tone) }
                    canvas.drawRect(p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat(), paint)
                }
                ByteArrayOutputStream().use { out ->
                    if (!cut.compress(Bitmap.CompressFormat.PNG, 100, out)) null else out.toByteArray()
                }
            } finally {
                cut.recycle()
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory trimming crop", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun toGray(source: ByteArray): GrayImage? {
        if (source.isEmpty()) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = try {
            BitmapFactory.decodeByteArray(source, 0, source.size, options)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory decoding crop for the blank check", e)
            null
        } ?: return null
        return try {
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            GrayImage.fromArgb(bitmap.width, bitmap.height, argb)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory reading crop pixels", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Applies EXIF orientation and re-encodes as PNG **at full resolution**.
     *
     * This is the registration path, not the grading path. Downscaling before
     * extraction would be a mistake twice over: ArUco detection needs every
     * module of a 60 px marker, and a crop cut from a downscaled page is
     * permanently softer than one cut from the original, so the model would be
     * asked to read handwriting that was thrown away before it ever saw it.
     *
     * Re-encoding is not optional even when the orientation is already normal.
     * `Imgcodecs.imdecode(..., IMREAD_COLOR)` honours a JPEG's EXIF orientation
     * itself, so handing the original bytes through would rotate the photo twice
     * whenever the tag is not normal. PNG carries no orientation tag, so a PNG
     * out of here is upright by construction and OpenCV has nothing left to
     * apply.
     *
     * Expect this to be expensive: a 12 MP photo is roughly 48 MB of ARGB_8888
     * bitmap plus the encoded output. Call it off the main thread and drop the
     * returned array as soon as extraction has finished with it.
     *
     * @return the PNG bytes, or null if [source] is not a decodable image or
     *   memory ran out. Never throws for bad input.
     */
    fun toRegistrationPng(source: ByteArray): ByteArray? {
        if (source.isEmpty()) {
            Log.w(TAG, "empty input bytes")
            return null
        }

        var working: Bitmap = try {
            BitmapFactory.decodeByteArray(
                source,
                0,
                source.size,
                // inSampleSize is left at 1. That is the whole point of this function.
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory decoding image at full resolution", e)
            null
        } ?: run {
            Log.w(TAG, "full-resolution decode returned no bitmap")
            return null
        }

        return try {
            working = applyOrientation(working, exifMatrix(source))

            ByteArrayOutputStream().use { out ->
                if (!working.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    Log.w(TAG, "PNG compress failed")
                    return null
                }
                val png = out.toByteArray()
                Log.i(
                    TAG,
                    "registration PNG ${working.width}x${working.height} " +
                        "inBytes=${source.size} outBytes=${png.size}"
                )
                png
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory preparing registration image", e)
            null
        } finally {
            working.recycle()
        }
    }

    /**
     * Reads the EXIF orientation tag and returns the matrix that corrects it, or
     * null when the image is already upright, carries no EXIF (e.g. PNG), or the
     * tag cannot be read.
     */
    private fun exifMatrix(source: ByteArray): Matrix? {
        val orientation = try {
            ByteArrayInputStream(source).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (t: Throwable) {
            // No EXIF, truncated EXIF, unsupported container: not an error, just
            // means there is nothing to correct.
            Log.i(TAG, "no readable EXIF orientation (${t.javaClass.simpleName})")
            return null
        }

        val transform = orientationTransform(orientation) ?: return null
        Log.i(TAG, "applying EXIF orientation=$orientation ($transform)")
        return Matrix().apply {
            // Rotate first, then flip: the same order the EXIF spec describes.
            if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
            if (transform.flipHorizontal) postScale(-1f, 1f)
            if (transform.flipVertical) postScale(1f, -1f)
        }
    }

    /**
     * The transform an image with the given EXIF orientation tag needs to become
     * upright, or null when there is nothing to do (normal, undefined, or a value
     * outside the eight the spec defines).
     *
     * Pure lookup, deliberately separate from the [Matrix] it feeds, so the
     * mapping can be tested without a graphics stack.
     */
    internal fun orientationTransform(orientation: Int): OrientationTransform? = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> OrientationTransform(90)
        ExifInterface.ORIENTATION_ROTATE_180 -> OrientationTransform(180)
        ExifInterface.ORIENTATION_ROTATE_270 -> OrientationTransform(270)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> OrientationTransform(0, flipHorizontal = true)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> OrientationTransform(0, flipVertical = true)
        ExifInterface.ORIENTATION_TRANSPOSE -> OrientationTransform(90, flipHorizontal = true)
        ExifInterface.ORIENTATION_TRANSVERSE -> OrientationTransform(270, flipHorizontal = true)
        // ORIENTATION_NORMAL, ORIENTATION_UNDEFINED, anything unexpected.
        else -> null
    }

    /** Clockwise rotation in degrees, then optional mirroring. */
    internal data class OrientationTransform(
        val rotationDegrees: Int,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false
    )

    /** Applies [matrix], recycling [bitmap] when a new one is produced. */
    private fun applyOrientation(bitmap: Bitmap, matrix: Matrix?): Bitmap {
        if (matrix == null) return bitmap
        val transformed = try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory rotating image; using unrotated bitmap", e)
            return bitmap
        }
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    /**
     * Scales so the long edge is exactly [maxLongEdge], preserving aspect ratio.
     * Images already within the limit are returned untouched — never upscaled.
     */
    private fun scaleToLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxLongEdge) return bitmap

        val ratio = maxLongEdge.toDouble() / longEdge
        val width = max(1, (bitmap.width * ratio).roundToInt())
        val height = max(1, (bitmap.height * ratio).roundToInt())

        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory scaling image; using unscaled bitmap", e)
            return bitmap
        }
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Largest power-of-two subsample that keeps the long edge at or above
     * [maxLongEdge], so the exact scale afterwards is always a downscale.
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        var sampleSize = 1
        var longEdge = max(width, height)
        while (longEdge / 2 >= maxLongEdge) {
            longEdge /= 2
            sampleSize *= 2
        }
        return sampleSize
    }
}
