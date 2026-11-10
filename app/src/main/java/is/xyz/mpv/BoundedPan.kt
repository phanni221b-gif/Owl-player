package `is`.xyz.mpv

import kotlin.math.pow

/**
 * Direct-manipulation math for mpv's bounded video-align panning.
 *
 * Pinned mpv video/out/aspect.c places the scaled picture of size S inside a window of size W at
 *   dst_start = (W - S) * (align + 1) / 2
 * so moving align by d moves the picture by -(S - W) * d / 2 pixels. To keep the picture under the
 * fingers, a finger movement of p pixels must change align by -2p / (S - W). A fixed per-window gain
 * (the 0.5.3 PAN_SENSITIVITY 0.55) instead moved the picture 0.275 * (zoom - 1) times the finger:
 * 0.275x at 2x zoom, 1.9x at 8x zoom.
 */
internal object BoundedPan {
    /** Below this overflow the picture fits the window on that axis and must stay centered. */
    const val MIN_OVERFLOW_PX = 1.0

    /** Displayed size at [zoom] (log2 video-zoom) from a size measured at [measuredZoom]. */
    fun extentAtZoom(measuredExtent: Double, measuredZoom: Double, zoom: Double): Double =
        measuredExtent * 2.0.pow(zoom - measuredZoom)

    /** New video-align value after the fingers moved [deltaPx] along one axis. */
    fun align(current: Double, deltaPx: Double, windowPx: Double, extentPx: Double): Double {
        val overflow = extentPx - windowPx
        if (!overflow.isFinite() || overflow < MIN_OVERFLOW_PX) return 0.0
        return (current - 2.0 * deltaPx / overflow).coerceIn(-1.0, 1.0)
    }

    /** Picture offset in pixels relative to a centered picture, for tests and diagnostics. */
    fun offsetPx(align: Double, windowPx: Double, extentPx: Double): Double =
        (windowPx - extentPx) * (align + 1.0) / 2.0 - (windowPx - extentPx) / 2.0
}
