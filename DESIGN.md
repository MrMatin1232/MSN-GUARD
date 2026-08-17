# MSN-GUARD Android design contract

This is a contract, not a description. If the app and this file disagree, one of
them is a bug, and which one is a decision — not a matter of taste.

The previous version of this document forbade "gradients, neon" while sitting
next to a codebase whose entire visual language is sculpted glass and accent
bloom. A contract nobody can follow is worse than no contract, because it stops
being read at all. Everything below matches what the code actually does.

## Intent

MSN-GUARD is a single-purpose connection console. It should feel like a precision
instrument in a dark room: calm at rest, unmistakable when something changes, and
honest about what it does and does not know.

Three consequences follow from that, and they outrank every aesthetic preference
in this file:

1. **The state is legible in under a second, from arm's length.** Connection
   state is carried by colour, by motion, AND by shape. Never by colour alone.
2. **The app never claims more than it has verified.** No "Protected" until the
   core reports bytes crossing the tunnel. No checkmark on a screen that says
   "Not connected". A reassuring graphic over an unproven state is a lie the UI
   is telling on the tunnel's behalf.
3. **The connect control is always reachable without scrolling.** Everything
   else on the home screen yields space before it does.

This is MSN-GUARD's own visual system. Any third-party app is an interaction
reference only; do not copy its code, wording, assets, or branding.

## Foundations

**Platform:** native Android views, platform typography, Android system bars. No
Compose, no Material components, no XML layouts. Every pixel is built in Kotlin
and every surface is drawn by `Sculpt`. Do not add a UI toolkit to style a
handful of widgets — the APK is already mostly native libraries, and the cost
would exceed the widgets.

**Tokens live in `Orbit.kt`.** Spacing, radii, type steps, and motion curves come
from there. Do not introduce a new dp literal, text size, or interpolator in a
component; add the token or use the nearest one. The rhythm of a screen is the
first thing an eye reads, and nine dialects of a similar rhythm read as
"unfinished" before anybody can name why.

**Colour lives in `AppAppearance.ORBIT`** — the Aurora Noir ramp. One fixed
palette, always dark, no theme picker and no Material You. Letting the OS repaint
this app produced washed-out greys and light-mode surfaces that a layout built
entirely from hand-drawn glass was never designed for.

| token | value | use |
| --- | --- | --- |
| `canvasTop` / `canvas` | `#080D14` → `#04060A` | the page, as a vertical lift |
| `surface` | `#0B1016` | raised glass |
| `surfaceLift` | `#101821` | glass stacked on glass |
| `surfaceVariant` | `#111922` | settings rows, recessed fills |
| `ink` | `#EDF6F4` | primary text |
| `inkDim` | `#C3D4D6` | de-emphasised primary text |
| `muted` | `#93A8B2` | secondary text |
| `faint` | `#5A6D78` | tertiary text, captions |
| `hairline` / `divider` | `#17202A` / `#1C2530` | borders |
| `primary` / `mint` | `#3FDFC6` | brand, download, connecting |
| `connected` | `#4FE79A` | verified tunnel |
| `violet` | `#9B8CFF` | upload |
| `amber` / `warning` | `#FFB13D` | speed, degraded |
| `danger` | `#FF6B85` | failure |
| `sky` | `#6FC6FF` | latency readout |

`ink` / `inkDim` / `muted` / `faint` are four distinct steps and must stay that
way. They were previously close enough that secondary and tertiary text looked
like the same tier printed twice, which is the same as having no hierarchy.

**Typography:** Roboto / Android system sans, plus monospace for anything the
user might read digit by digit (addresses, timers, byte counts). Five type steps,
defined in `Orbit.Type`. Do not add a sixth, and never add one 0.5sp from an
existing step — that is not hierarchy, it is noise.

## Material: the six-layer surface

Every surface goes through `Sculpt` / `GlassDrawable`, which paints, in order:

1. body gradient — three stops, never two
2. a wide soft specular across the upper half — the light source
3. a tight hotspot near the top-left corner — the curvature cue
4. ambient occlusion along the bottom — the contact cue
5. a bevel, bright on the top edge, fading around the sides
6. an optional outer bloom — for a surface that is *lit*, not merely present

Layers 3 and 4 are the ones that matter and the ones that get dropped. Without a
corner hotspot there is no curvature; without occlusion there is no contact; and
without either, the brain has nothing to build a three-dimensional reading from,
so the result is a flat rounded rectangle with a border no matter how good the
body gradient is.

**A press inverts the vertical lighting and moves the occlusion to the top edge.**
Pressed surfaces sink. They do not merely darken and they do not merely scale.

## Home screen

- The top of the screen stays quiet: a status LED, the wordmark as a small muted
  caption, and the settings entry. No logo, no hero card. The brand lives on the
  launcher icon and the opening splash.
- The dial is the visual centre and the only large filled control. It is the
  last element allowed to lose space, and it shrinks (via `sizeScale`) before the
  screen is ever allowed to scroll.
- Below it, in order: connection title, detail line, latency/protocol chips,
  three metric tiles, the exit-node card, the transport rail, the action bar, the
  footer trace.
- The app is VPN-mode only; there is no mode selector. The transport rail is the
  single full-width selector, and it is **disabled while a tunnel is active** —
  disabled meaning the track dims and the thumb's glow goes out, so it reads as
  locked rather than broken.
- Real connection wording only: `Not connected`, `Connecting`, `Verifying`,
  `Connected`, `Connection failed`.

### The three metric tiles

One accent each — mint DOWN, violet UP, amber SPEED — repeated in four places per
tile (dot, caption, bars, underglow). This is not decoration. Three counters of
identical colour and identical layout side by side cannot be told apart without
reading all three captions, which defeats the point of an at-a-glance row.

Values cross-dissolve on change. Never teleport a number: a figure that jumps
looks the same whether it is moving or stalled.

## The dial: state → appearance

| state | accent | gauge | glyph | caption |
| --- | --- | --- | --- | --- |
| `DISCONNECTED` | `muted` | dark | **open** padlock | `TAP TO CONNECT` |
| `CONNECTING` | `primary` | breathing thirds | closed padlock | `CONNECTING` |
| `CONNECTED` | `connected` | filled, with a head | session timer | `SESSION` |
| `DEGRADED` | `warning` | filled | session timer | `NO TRAFFIC` |
| `FAILED` | `danger` | dark | broken link | `TAP TO RETRY` |

Every row differs by **shape as well as colour**. That is the accessibility floor
and it is also just correct: a user glancing at a green-vs-amber difference in
sunlight has nothing to go on otherwise.

The gauge is 72 ticks with every sixth drawn long and bright. The grouping is
load-bearing: with identical ticks, a partially filled gauge is just "some
green", because the eye has no landmarks to count against.

The progress arc carries a lit head that orbits along it. An arc that pulses says
"something is happening"; a dot travelling along it says "happening at a rate",
which is the difference between a user waiting and a user tapping again.

### Dial geometry — do not simplify

The aura and the ripples deliberately paint outside the ring. The view runs with
`LAYER_TYPE_SOFTWARE`, so Android allocates an offscreen bitmap exactly the size
of the **view** and discards everything outside it before any parent gets a say
— `clipChildren=false` on ancestors cannot rescue it. Therefore:

- the measured box is always `ring + BLEED_DP`;
- `BLEED_DP` is **derived** from ripple growth and aura reach, never hand-tuned;
- `sizeScale` scales the box and the ring by the same factor, so shrinking can
  never squeeze the bleed out from under the glow.

## Motion

- Curves come from `Orbit.Motion`. `STANDARD` for anything moving on its own,
  `SPRINGY` only for a control that follows a finger, `EXIT` for things leaving.
- **No overshoot on a control that selects something.** The transport thumb used
  to fly past the protocol the user picked and come back; on the control that
  decides how traffic leaves the country, that is a control appearing to disagree
  with you.
- Interaction motion stays under 340ms. Ambient loops (aura breath, sheen,
  footer wave) run at `Orbit.Motion.AMBIENT`.
- A press scales the dial to 96.5% for 90ms and settles back over 230ms.
- State changes crossfade their accent over 420ms. A one-frame colour swap reads
  as a repaint, not as an event.
- Standard Android context-click haptic on any intentional state change.

## Accessibility

- Every interactive element has a content description, and that description is
  **resynced when the state changes**. A toggle that reports its constructor-time
  value forever is a correctness bug, not a polish one — especially for a kill
  switch.
- Colour never carries state alone. Shape, weight, or wording carries it too.
- Minimum touch target 48dp; settings rows are 56dp and toggle rows 60dp.
- Screen readers get the full unshortened IP address; the visual keeps the
  fitted one. Never put a truncated address on the clipboard.
- Focus states are visible on every focusable surface (TV / keyboard / D-pad).

## Performance rules for the hand-rolled tickers

This app is often left open for hours next to a live tunnel, so decoration has a
battery cost and the rules are not optional.

- Ambient tickers run at `Orbit.Motion.TICK_MS` (20fps), not per frame.
- Anything animating must stop on `onDetachedFromWindow`, and must not run while
  its own state makes it invisible — the footer wave posts nothing when unlit.
- Easing animations must have a settle threshold. Never chase an asymptote.
- Build drawables once and swap them. Do not allocate a `GlassDrawable` per
  touch event.
- `LAYER_TYPE_SOFTWARE` only where `setShadowLayer` or an exact sweep gradient
  requires it, and only on small views.

## Guardrails

- No copied third-party assets, names, code, screenshots, or branding.
- No fake statistics, no invented progress, no nested-card dashboards.
- No reassurance graphics over unverified states.
- No new UI dependency for this screen set. Revisit only if the app grows enough
  screens to justify a Compose migration — and then migrate, do not mix.
