package com.example.capstone.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Robolectric with native graphics, so these run against real Skia encode and
 * decode rather than stubs: the byte output is a genuine PNG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePrepTest {

    @Test
    fun `a 4000x3000 photo is downscaled to a 1024 long edge`() {
        val out = ImagePrep.toGradingPng(sourceImage(4000, 3000))

        assertThat(out).isNotNull()
        val (width, height) = dimensionsOf(out!!)
        assertThat(width).isEqualTo(1024)
        assertThat(height).isEqualTo(768)
    }

    @Test
    fun `downscaling preserves the aspect ratio`() {
        val out = ImagePrep.toGradingPng(sourceImage(4000, 3000))!!

        val (width, height) = dimensionsOf(out)
        assertThat(width.toDouble() / height).isWithin(0.01).of(4000.0 / 3000.0)
    }

    @Test
    fun `an 800x600 photo is left alone and never upscaled`() {
        val out = ImagePrep.toGradingPng(sourceImage(800, 600))

        assertThat(out).isNotNull()
        assertThat(dimensionsOf(out!!)).isEqualTo(800 to 600)
    }

    @Test
    fun `a portrait 3000x4000 photo keeps its long edge on the height`() {
        val out = ImagePrep.toGradingPng(sourceImage(3000, 4000))

        assertThat(out).isNotNull()
        val (width, height) = dimensionsOf(out!!)
        assertThat(height).isEqualTo(1024)
        assertThat(width).isEqualTo(768)
        assertThat(height).isGreaterThan(width)
    }

    @Test
    fun `no output edge ever exceeds the limit`() {
        val cases = listOf(4000 to 3000, 3000 to 4000, 2048 to 2048, 1025 to 100, 800 to 600)

        for ((w, h) in cases) {
            val (outW, outH) = dimensionsOf(ImagePrep.toGradingPng(sourceImage(w, h))!!)
            assertThat(maxOf(outW, outH)).isAtMost(ImagePrep.MAX_LONG_EDGE)
        }
    }

    @Test
    fun `output is valid png bytes`() {
        val out = ImagePrep.toGradingPng(sourceImage(1600, 1200))!!

        // PNG file signature, per the spec.
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        assertThat(out.copyOfRange(0, signature.size)).isEqualTo(signature)

        // And it round-trips back through the decoder.
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertThat(decoded).isNotNull()
        assertThat(decoded.width).isEqualTo(1024)
    }

    @Test
    fun `undecodable input returns null instead of throwing`() {
        assertThat(ImagePrep.toGradingPng(ByteArray(0))).isNull()
        assertThat(ImagePrep.toGradingPng("not an image at all".toByteArray())).isNull()
    }

    /**
     * A JPEG-encoded source with content in it, matching what the camera and the
     * gallery hand over. Content matters: a uniform fill would let the encoders
     * collapse the image and hide sizing mistakes.
     */
    private fun sourceImage(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(0f, 0f, width / 3f, height / 3f, paint)
            return ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "encode failed" }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads the encoded size without allocating the pixels. */
    private fun dimensionsOf(png: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, options)
        return options.outWidth to options.outHeight
    }
}
