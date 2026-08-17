package com.msnguard.vpn

import android.content.Context

/**
 * The Aurora Noir palette — one fixed design language, no theme switcher.
 *
 * There is no colour picker and no "Dynamic" mode on purpose. Letting the OS
 * repaint this app produced washed-out greys and light-mode surfaces that a
 * layout built entirely from hand-drawn glass was never designed for.
 *
 * WHAT CHANGED IN THIS PASS, and why each one is not a taste call:
 *
 *  * The canvas was a single flat near-black. A phone screen full of one flat
 *    colour has no depth cue at all, so the sculpted cards had nothing to sit
 *    *on* — they read as stickers. [canvasTop] / [canvas] give the page a very
 *    slight vertical lift (about 3% luminance) which is enough for the eye to
 *    place the surfaces above it and costs one gradient.
 *  * `muted` and `faint` were 0xFF9DB0B5 and 0xFF5F7276 — close enough that
 *    secondary and tertiary text looked like the same tier printed twice.
 *    They are now separated, and [inkDim] fills the step between primary text
 *    and secondary so three real levels exist.
 *  * The accents were re-tuned for OLED at low brightness, which is the actual
 *    condition this app is used in. The old amber (0xFFFFC46B) and the old
 *    mint sat at nearly identical perceived lightness, so an amber SPEED tile
 *    and a mint DOWN tile had the same visual weight and the eye could not
 *    rank them.
 *
 * [Palette] keeps every field name the rest of the app already used. New
 * tokens are appended with defaults derived from the old ones, so nothing that
 * constructs or reads a Palette had to change.
 */
object AppAppearance {

    data class Palette(
        /** page background, bottom of the vertical lift */
        val canvas: Int,
        /** raised card fill */
        val surface: Int,
        /** recessed / secondary card fill */
        val surfaceVariant: Int,
        /** primary text */
        val ink: Int,
        /** secondary text */
        val muted: Int,
        /** hairline borders */
        val divider: Int,
        /** the brand accent */
        val primary: Int,
        /** text drawn on top of [primary] */
        val primaryContainer: Int,
        /** the "tunnel is up" accent */
        val connected: Int,
        val connectedContainer: Int,
        /** tertiary text — the quietest legible step */
        val faint: Int,
        /** download accent */
        val mint: Int,
        /** upload accent */
        val violet: Int,
        /** in-progress / speed accent */
        val amber: Int,
        /** failure accent */
        val danger: Int,

        // ---- added in the Aurora pass; all defaulted, all optional ----

        /** top of the page's vertical lift; pair with [canvas] for the backdrop */
        val canvasTop: Int = canvas,
        /** a surface one step brighter than [surface], for stacked glass */
        val surfaceLift: Int = surface,
        /** the quietest visible border — thinner in feel than [divider] */
        val hairline: Int = divider,
        /** the step between [ink] and [muted]; de-emphasised primary text */
        val inkDim: Int = muted,
        /** the colour every glow and bloom is tinted with when nothing is lit */
        val glow: Int = primary,
        /** "needs attention" that is not yet a failure */
        val warning: Int = amber,
        /** informational accent, used by the latency readout */
        val sky: Int = primary,
    )

    val ORBIT = Palette(
        canvas = 0xFF04060A.toInt(),
        surface = 0xFF0B1016.toInt(),
        surfaceVariant = 0xFF111922.toInt(),
        ink = 0xFFEDF6F4.toInt(),
        muted = 0xFF93A8B2.toInt(),
        divider = 0xFF1C2530.toInt(),
        primary = 0xFF3FDFC6.toInt(),
        primaryContainer = 0xFF04060A.toInt(),
        connected = 0xFF4FE79A.toInt(),
        connectedContainer = 0xFF0D3222.toInt(),
        faint = 0xFF5A6D78.toInt(),
        mint = 0xFF3FDFC6.toInt(),
        violet = 0xFF9B8CFF.toInt(),
        amber = 0xFFFFB13D.toInt(),
        danger = 0xFFFF6B85.toInt(),
        canvasTop = 0xFF080D14.toInt(),
        surfaceLift = 0xFF101821.toInt(),
        hairline = 0xFF17202A.toInt(),
        inkDim = 0xFFC3D4D6.toInt(),
        glow = 0xFF3FDFC6.toInt(),
        warning = 0xFFFFB13D.toInt(),
        sky = 0xFF6FC6FF.toInt(),
    )

    /** Kept as a function so the old `AppAppearance.load(this)` call sites work. */
    @Suppress("UNUSED_PARAMETER")
    fun load(context: Context): Palette = ORBIT

    /** The app is dark, always. Callers use this to pick system-bar icon colour. */
    @Suppress("UNUSED_PARAMETER")
    fun isNight(context: Context): Boolean = true
}
