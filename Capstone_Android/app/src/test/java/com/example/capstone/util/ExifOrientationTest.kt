package com.example.capstone.util

import androidx.exifinterface.media.ExifInterface
import com.example.capstone.util.ImagePrep.OrientationTransform
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Phone cameras almost never write upright pixels; they write sideways pixels
 * plus an orientation tag. Get this mapping wrong and the model is asked to read
 * handwriting rotated 90 degrees, so every tag the EXIF spec defines is pinned
 * here individually.
 */
class ExifOrientationTest {

    @Test
    fun `rotate 90 tag maps to a 90 degree rotation`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_ROTATE_90))
            .isEqualTo(OrientationTransform(rotationDegrees = 90))
    }

    @Test
    fun `rotate 180 tag maps to a 180 degree rotation`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_ROTATE_180))
            .isEqualTo(OrientationTransform(rotationDegrees = 180))
    }

    @Test
    fun `rotate 270 tag maps to a 270 degree rotation`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_ROTATE_270))
            .isEqualTo(OrientationTransform(rotationDegrees = 270))
    }

    @Test
    fun `flip horizontal tag mirrors without rotating`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
            .isEqualTo(OrientationTransform(rotationDegrees = 0, flipHorizontal = true))
    }

    @Test
    fun `flip vertical tag mirrors without rotating`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_FLIP_VERTICAL))
            .isEqualTo(OrientationTransform(rotationDegrees = 0, flipVertical = true))
    }

    @Test
    fun `transpose tag rotates 90 then mirrors`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_TRANSPOSE))
            .isEqualTo(OrientationTransform(rotationDegrees = 90, flipHorizontal = true))
    }

    @Test
    fun `transverse tag rotates 270 then mirrors`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_TRANSVERSE))
            .isEqualTo(OrientationTransform(rotationDegrees = 270, flipHorizontal = true))
    }

    @Test
    fun `normal tag needs no transform`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_NORMAL)).isNull()
    }

    @Test
    fun `undefined tag needs no transform`() {
        assertThat(ImagePrep.orientationTransform(ExifInterface.ORIENTATION_UNDEFINED)).isNull()
    }

    /** A garbage tag must fall through to "leave it alone", never to a rotation. */
    @Test
    fun `out of range tags need no transform`() {
        for (tag in listOf(-7, 0, 9, 42, Int.MAX_VALUE)) {
            assertThat(ImagePrep.orientationTransform(tag)).isNull()
        }
    }

    /** No tag may map to a rotation outside the four quarter turns. */
    @Test
    fun `every defined tag maps to a quarter turn`() {
        val allTags = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )

        for (tag in allTags) {
            val transform = ImagePrep.orientationTransform(tag) ?: continue
            assertThat(transform.rotationDegrees).isIn(listOf(0, 90, 180, 270))
            // Nothing in the spec asks for both mirrors at once.
            assertThat(transform.flipHorizontal && transform.flipVertical).isFalse()
        }
    }
}
