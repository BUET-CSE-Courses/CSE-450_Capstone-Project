package com.example.capstone.domain.grading

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Test resources under `src/test/resources/grading/`, and image helpers for the JVM. */
object Fixtures {

    fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("grading/$name")) {
            "missing test resource grading/$name"
        }.use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    fun gray(name: String): GrayImage = checkNotNull(decodeGray(bytes(name))) { "cannot decode $name" }

    /**
     * The JVM stand-in for `ImagePrep.toGray`: decode, then PIL's luma. Null
     * for bytes that are not an image, as PIL's `Image.open` would fail.
     */
    fun decodeGray(bytes: ByteArray): GrayImage? {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return GrayImage.fromArgb(image.width, image.height, argb)
    }

    /** A PNG of [lines] in black on [background], drawn with AWT's own font. */
    fun png(
        lines: List<String> = emptyList(),
        background: Color = Color.WHITE,
        width: Int = 900,
        height: Int = 300
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = background
            g.fillRect(0, 0, width, height)
            g.color = Color.BLACK
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 40)
            lines.forEachIndexed { i, line -> g.drawString(line, 30, 60 + i * 70) }
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    /** A flat [value] image with [ink] pixels of 0 placed away from the edges. */
    fun flat(width: Int, height: Int, value: Int, ink: Int = 0): GrayImage {
        val pixels = IntArray(width * height) { value }
        var placed = 0
        loop@ for (y in height / 4 until height * 3 / 4) {
            for (x in width / 4 until width * 3 / 4) {
                if (placed == ink) break@loop
                pixels[y * width + x] = 0
                placed++
            }
        }
        check(placed == ink) { "could not place $ink ink pixels" }
        return GrayImage(width, height, pixels)
    }
}
