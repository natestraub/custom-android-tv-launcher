package dev.tvlauncher.wallpaper

import kotlin.math.max
import kotlin.math.roundToInt

data class CropBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Decode at [scaledW]x[scaledH], then crop to exactly the screen. */
data class CoverPlan(val scaledW: Int, val scaledH: Int, val crop: CropBox)

object ImageMath {
    /**
     * Center-crop plan: scale the source so it covers the destination, then
     * crop the overflow. The decoded bitmap is exactly dstW x dstH, so drawing
     * it is a 1:1 blit with no per-frame scaling.
     */
    fun coverPlan(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CoverPlan {
        val scale = max(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        val w = max(dstW, (srcW * scale).roundToInt())
        val h = max(dstH, (srcH * scale).roundToInt())
        val left = (w - dstW) / 2
        val top = (h - dstH) / 2
        return CoverPlan(w, h, CropBox(left, top, left + dstW, top + dstH))
    }
}
