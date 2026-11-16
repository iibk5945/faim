package com.yyh.gametools

import android.graphics.Bitmap

object Detector {

    data class Target(val x: Float, val y: Float, val hits: Int)

    private const val COLS = 24
    private const val ROWS = 11
    private const val MIN_HITS = 6

    fun detect(src: Bitmap, srcW: Int, srcH: Int): Target? {
        val small = Bitmap.createScaledBitmap(src, COLS * 8, ROWS * 8, false)
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()

        val cells = IntArray(COLS * ROWS)

        for (y in 0 until h) {
            val cy = y / 8
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 135 && g < 100 && b < 100 && r - g > 55) {
                    cells[cy * COLS + x / 8]++
                }
            }
        }

        var best = -1
        var bestHits = MIN_HITS
        for (i in cells.indices) {
            if (cells[i] > bestHits) {
                bestHits = cells[i]
                best = i
            }
        }
        if (best < 0) return null

        val cx = (best % COLS + 0.5f) / COLS
        val cy = (best / COLS + 0.5f) / ROWS
        return Target(cx * srcW, cy * srcH, bestHits)
    }
}
