package com.example.capstone.domain.grading

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class CropTrimTest {

    /** Where every trimmed image is saved, to be looked at (build/ is not committed). */
    private val outDir = File("build/trim-check/out").apply { mkdirs() }

    /**
     * Optional real phone crops, copied here by hand (never committed: build/ is ignored, and
     * a student's worked answer can give the answers away). Each is checked like the
     * synthetic ones, with the label region the pack would give a one-part box of its size.
     */
    private val realInputs = File("build/trim-check/input")

    /** A 934 x 781 box like session 7's box 2: grey paper, the printed label, two written lines. */
    private fun boxWithLabelAndTwoLines(): BufferedImage {
        val image = BufferedImage(934, 781, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(172, 172, 172)
        g.fillRect(0, 0, 934, 781)
        g.color = Color(40, 40, 40)
        g.font = java.awt.Font(java.awt.Font.SERIF, java.awt.Font.BOLD, 17)
        g.drawString("☐ answer", 8, 26)
        g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 34)
        g.drawString("a)  x = 6 / 2 = 3", 20, 95)
        g.drawString("b)  y = 2 + 3 = 5", 20, 205)
        g.dispose()
        return image
    }

    private val label934 = PrintedLabel.region("", 0, 1, 934, 781, 150)

    @Test
    fun `a box written at the top is cut to the writing, widened to 2 to 1, label painted out`() {
        val image = boxWithLabelAndTwoLines()
        val gray = grayOf(image)
        val plan = checkNotNull(CropTrim.plan(gray, label934))

        // Every ink pixel outside the label is kept.
        val ink = inkOutsideLabel(gray, label934)
        assertThat(ink).isNotEmpty()
        ink.forEach { (x, y) ->
            assertThat(x in plan.cut.left until plan.cut.right && y in plan.cut.top until plan.cut.bottom).isTrue()
        }
        // Much less than the whole box, and not a thin strip.
        assertThat(plan.cut.height).isLessThan(781 / 2 + 60)
        assertThat(plan.cut.width.toDouble() / plan.cut.height).isAtMost(CropTrim.MAX_ASPECT + 0.01)

        val out = apply(image, plan)
        save(out, "synthetic_box2")
        // No label ink survives: the painted area is paper, and no dark pixel is left there.
        val labelInCut = label934!!.toPixels(934, 781)
        for (y in labelInCut.top until labelInCut.bottom) for (x in labelInCut.left until labelInCut.right) {
            val cx = x - plan.cut.left
            val cy = y - plan.cut.top
            if (cx in 0 until out.width && cy in 0 until out.height) {
                assertThat(GrayImage.luma((out.getRGB(cx, cy) shr 16) and 0xFF, (out.getRGB(cx, cy) shr 8) and 0xFF, out.getRGB(cx, cy) and 0xFF))
                    .isAtLeast(plan.paperTone - 1)
            }
        }
        assertThat(plan.paperTone).isEqualTo(172) // the flat paper around the label
    }

    @Test
    fun `the phone's blank box gets no plan, so it would go whole (it is never sent anyway)`() {
        val photo = Fixtures.gray("phone_blank_box_with_label.png")
        val label = PrintedLabel.region("", 0, 1, 934, 781, 150)
        assertThat(CropTrim.plan(photo, label)).isNull()
    }

    @Test
    fun `writing that fills the box is not cut, but the label is still painted out`() {
        val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, 400, 300)
        g.color = Color.BLACK
        for (y in 20 until 290 step 30) g.drawLine(15, y, 385, y)
        g.dispose()
        val label = IgnoreRegion(0.0, 0.0, 0.2, 0.1)
        val plan = checkNotNull(CropTrim.plan(grayOf(image), label))
        assertThat(plan.cut).isEqualTo(BlankDetector.PixelRect(0, 0, 400, 300))
        assertThat(plan.paint).isEqualTo(BlankDetector.PixelRect(0, 0, 80, 30))
        save(apply(image, plan), "synthetic_full")
    }

    @Test
    fun `no label and nothing to cut means no plan`() {
        val full = IntArray(400 * 300) { i -> if (i % 5 == 0) 30 else 200 }
        assertThat(CropTrim.plan(GrayImage(400, 300, full), null)).isNull()
    }

    @Test
    fun `widening stays inside the image and centres when it can`() {
        // 600 x 50 needs 300 high: centred it would start at -25, so it is shifted down to 0.
        assertThat(CropTrim.widened(BlankDetector.PixelRect(100, 100, 700, 150), 934, 781))
            .isEqualTo(BlankDetector.PixelRect(100, 0, 700, 300))
        assertThat(CropTrim.widened(BlankDetector.PixelRect(100, 300, 700, 350), 934, 781))
            .isEqualTo(BlankDetector.PixelRect(100, 175, 700, 475))
        // Tall and narrow grows sideways; never past the image.
        assertThat(CropTrim.widened(BlankDetector.PixelRect(900, 0, 930, 700), 934, 781))
            .isEqualTo(BlankDetector.PixelRect(584, 0, 934, 700))
        // Already within 2 : 1: unchanged.
        val ok = BlankDetector.PixelRect(10, 10, 210, 110)
        assertThat(CropTrim.widened(ok, 934, 781)).isEqualTo(ok)
    }

    @Test
    fun `real phone crops keep every written pixel`() {
        val inputs = realInputs.listFiles { f -> f.extension.equals("png", ignoreCase = true) }.orEmpty()
        for (file in inputs.sortedBy { it.name }) {
            val image = ImageIO.read(file)
            val gray = grayOf(image)
            val label = PrintedLabel.region("", 0, 1, image.width, image.height, 150)
            val plan = CropTrim.plan(gray, label)
            if (plan == null) {
                println("${file.name}: no plan (${image.width}x${image.height}), goes whole")
                continue
            }
            inkOutsideLabel(gray, label).forEach { (x, y) ->
                assertThat(x in plan.cut.left until plan.cut.right && y in plan.cut.top until plan.cut.bottom).isTrue()
            }
            val out = apply(image, plan)
            save(out, file.nameWithoutExtension + "_trimmed")
            println("${file.name}: ${image.width}x${image.height} -> cut ${plan.cut} (${out.width}x${out.height}) paint ${plan.paint} tone ${plan.paperTone}")
        }
    }

    // ---- helpers ------------------------------------------------------------------

    private fun grayOf(image: BufferedImage): GrayImage =
        GrayImage.fromArgb(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))

    /** Ink as [BlankDetector] counts it, inside the 4 % edge and outside [label]. */
    private fun inkOutsideLabel(gray: GrayImage, label: IgnoreRegion?): List<Pair<Int, Int>> {
        val tone = BlankDetector.paperTone(gray, label)
        val mX = (gray.width * BlankDetector.EDGE_TRIM).toInt()
        val mY = (gray.height * BlankDetector.EDGE_TRIM).toInt()
        val skip = label?.toPixels(gray.width, gray.height)
        val out = mutableListOf<Pair<Int, Int>>()
        for (y in mY until gray.height - mY) for (x in mX until gray.width - mX) {
            if (skip != null && x in skip.left until skip.right && y in skip.top until skip.bottom) continue
            if (gray[x, y] < tone - BlankDetector.INK_DARKER_THAN_PAPER) out += x to y
        }
        return out
    }

    /** The JVM mirror of `ImagePrep.trimmedPng`: cut, then paint the label in the paper tone. */
    private fun apply(image: BufferedImage, plan: CropTrim.Plan): BufferedImage {
        val cut = BufferedImage(plan.cut.width, plan.cut.height, BufferedImage.TYPE_INT_RGB)
        val g = cut.createGraphics()
        g.drawImage(image, 0, 0, plan.cut.width, plan.cut.height, plan.cut.left, plan.cut.top, plan.cut.right, plan.cut.bottom, null)
        plan.paint?.let { p ->
            g.color = Color(plan.paperTone, plan.paperTone, plan.paperTone)
            g.fillRect(p.left, p.top, p.width, p.height)
        }
        g.dispose()
        return cut
    }

    private fun save(image: BufferedImage, name: String) {
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }
}
