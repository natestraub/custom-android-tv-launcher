package dev.tvlauncher.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMathTest {
    @Test
    fun `wide photo is scaled to screen height and cropped on the sides`() {
        val plan = ImageMath.coverPlan(srcW = 4000, srcH = 2000, dstW = 1920, dstH = 1080)
        assertEquals(2160, plan.scaledW)
        assertEquals(1080, plan.scaledH)
        assertEquals(CropBox(120, 0, 2040, 1080), plan.crop)
    }

    @Test
    fun `tall phone photo is scaled to screen width and cropped top and bottom`() {
        val plan = ImageMath.coverPlan(srcW = 3000, srcH = 4000, dstW = 1920, dstH = 1080)
        assertEquals(1920, plan.scaledW)
        assertEquals(2560, plan.scaledH)
        assertEquals(CropBox(0, 740, 1920, 1820), plan.crop)
    }

    @Test
    fun `exact 16x9 source maps one to one`() {
        val plan = ImageMath.coverPlan(srcW = 3840, srcH = 2160, dstW = 1920, dstH = 1080)
        assertEquals(1920, plan.scaledW)
        assertEquals(1080, plan.scaledH)
        assertEquals(CropBox(0, 0, 1920, 1080), plan.crop)
    }

    @Test
    fun `small image is scaled up to cover the screen`() {
        val plan = ImageMath.coverPlan(srcW = 960, srcH = 540, dstW = 1920, dstH = 1080)
        assertEquals(1920, plan.scaledW)
        assertEquals(1080, plan.scaledH)
    }

    @Test
    fun `crop is always exactly the screen size`() {
        val plan = ImageMath.coverPlan(srcW = 4031, srcH = 3023, dstW = 1920, dstH = 1080)
        assertEquals(1920, plan.crop.right - plan.crop.left)
        assertEquals(1080, plan.crop.bottom - plan.crop.top)
    }
}
