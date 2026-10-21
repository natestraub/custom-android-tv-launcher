package dev.tvlauncher.ui

object ScrollMath {
    /**
     * New translationX for a horizontally scrolling strip so the item spanning
     * [itemStart, itemEnd] (strip coordinates) is visible with [margin] px of
     * room on either side. Moves the minimum distance; clamps to content edges.
     */
    fun targetOffset(
        itemStart: Int,
        itemEnd: Int,
        viewport: Int,
        content: Int,
        margin: Int,
        current: Float,
    ): Float {
        var offset = current
        if (itemStart - margin + offset < 0) offset = (margin - itemStart).toFloat()
        if (itemEnd + margin + offset > viewport) offset = (viewport - itemEnd - margin).toFloat()
        val min = minOf(0, viewport - content).toFloat()
        return offset.coerceIn(min, 0f)
    }
}
