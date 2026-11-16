package com.yyh.gametools

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.random.Random

object Human {
    fun reactionMs(): Long = 85L + Random.nextLong(95L)

    fun stepFraction(remaining: Float): Float {
        val base = when {
            remaining > 400f -> 0.34f
            remaining > 150f -> 0.26f
            else -> 0.18f
        }
        return base * (0.85f + Random.nextFloat() * 0.30f)
    }

    fun jitter(): Float = Random.nextFloat() * 2.4f - 1.2f

    const val DEADZONE = 6f
    const val NEW_TARGET_PX = 90f
}

class Aim(private val sens: Float) {

    data class Move(val dx: Float, val dy: Float)

    private var engagedAt = 0L
    private var last: Detector.Target? = null

    fun reset() {
        last = null
        engagedAt = 0L
    }

    fun update(center: PointF, t: Detector.Target?, now: Long): Move? {
        if (t == null) {
            reset()
            return null
        }

        val prev = last
        if (prev == null || hypot(t.x - prev.x, t.y - prev.y) > Human.NEW_TARGET_PX) {
            engagedAt = now
        }
        last = t

        if (now - engagedAt < Human.reactionMs()) return null

        val dx = t.x - center.x
        val dy = t.y - center.y
        val d = hypot(dx, dy)
        if (d < Human.DEADZONE) return null

        val f = Human.stepFraction(d) * sens
        return Move(dx * f + Human.jitter(), dy * f + Human.jitter())
    }
}
