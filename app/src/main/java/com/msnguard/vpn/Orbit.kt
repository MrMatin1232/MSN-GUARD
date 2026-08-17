package com.msnguard.vpn

import android.content.Context
import android.graphics.Typeface
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/**
 * Orbit design tokens — the single source of truth for spacing, corner radii,
 * type steps and motion in this app.
 *
 * WHY THIS FILE EXISTS. There are no XML layouts here: every pixel is built in
 * Kotlin. That was fine for one screen and stopped being fine at six. Each
 * component had grown its own dp literals (13 here, 11 there, 9 somewhere
 * else), its own text sizes and its own hand-rolled interpolator, so the app
 * was not one design language, it was nine dialects of a similar one. The
 * rhythm of a screen is the first thing an eye reads, and an irregular rhythm
 * reads as "unfinished" long before anybody can name why.
 *
 * Everything below is deliberately small and boring. The value is not in any
 * one number, it is in there being exactly one of each.
 */
object Orbit {

    /**
     * Vertical and horizontal rhythm, in dp.
     *
     * A 4/6/10/14 progression rather than a strict 8-point grid: this UI is
     * dense by nature (a dial, three counters, a card and two bars on one
     * portrait screen with no scrolling), and an 8-point grid forces either
     * wasted air or collisions at that density.
     */
    object Space {
        const val HAIR = 2
        const val XXS = 4
        const val XS = 6
        const val S = 10
        const val M = 14
        const val L = 18
        const val XL = 24
        const val XXL = 30
    }

    /** Corner radii. [PILL] is intentionally absurd; GlassDrawable clamps it. */
    object Radius {
        const val CHIP = 12
        const val WELL = 14
        const val TILE = 20
        const val CARD = 24
        const val PILL = 999
    }

    /**
     * Type scale.
     *
     * Five steps, no more. The old screens used eleven distinct text sizes
     * between 8sp and 22sp, several of them 0.5sp apart, which is a difference
     * nobody perceives as hierarchy and everybody perceives as sloppiness.
     */
    object Type {
        const val DISPLAY = 26f
        const val TITLE = 21f
        const val BODY = 15f
        const val LABEL = 13f
        const val CAPTION = 11.5f
        const val OVERLINE = 8.8f

        /** Tracking. Small uppercase captions need it; body text does not. */
        const val TRACK_OVERLINE = 0.16f
        const val TRACK_LABEL = 0.06f

        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MONO: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    /**
     * Motion.
     *
     * [STANDARD] for anything that moves on its own, [SPRINGY] only for a
     * control that follows a finger (the transport thumb), [EXIT] for things
     * leaving the screen — an exit that decelerates looks reluctant.
     *
     * Durations stay under 340ms. This is a one-tap utility; a user who opens
     * it wants a tunnel, not choreography.
     */
    object Motion {
        val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EMPHASIZED: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val EXIT: Interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        val SPRINGY: Interpolator = PathInterpolator(0.18f, 1.12f, 0.3f, 1f)

        const val INSTANT = 90L
        const val FAST = 150L
        const val BASE = 230L
        const val SLOW = 340L

        /** One turn of any ambient loop (halo breath, sheen sweep, footer wave). */
        const val AMBIENT = 4_600L

        /**
         * Frame budget for the hand-rolled tickers.
         *
         * 50ms, not 16ms. These are ambient decorations on a screen that is
         * often left open for hours next to a live tunnel; 20fps is
         * indistinguishable for a slow wave and costs a third of the wakeups.
         */
        const val TICK_MS = 50L
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun dpf(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density
}
