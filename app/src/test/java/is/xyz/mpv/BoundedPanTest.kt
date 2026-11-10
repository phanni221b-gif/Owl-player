package `is`.xyz.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedPanTest {
    private val window = 2000.0

    private fun movedPx(zoom: Double, fingerPx: Double): Double {
        val extent = BoundedPan.extentAtZoom(window, 0.0, zoom) // fitted on this axis at zoom 0
        val align = BoundedPan.align(0.0, fingerPx, window, extent)
        return BoundedPan.offsetPx(align, window, extent) - BoundedPan.offsetPx(0.0, window, extent)
    }

    @Test fun pictureFollowsTheFingerOneToOneAtEveryZoom() {
        for (zoom in listOf(0.5, 1.0, 2.0, 3.0)) {
            assertEquals("zoom=$zoom right", 100.0, movedPx(zoom, 100.0), 1e-6)
            assertEquals("zoom=$zoom left", -100.0, movedPx(zoom, -100.0), 1e-6)
        }
    }

    @Test fun legacyFixedGainWasZoomDependent() {
        // 0.5.3: align -= dx / W * 0.55 -> picture moved 0.275 * (scale - 1) * finger.
        fun legacy(zoom: Double): Double {
            val extent = BoundedPan.extentAtZoom(window, 0.0, zoom)
            val align = -100.0 / window * 0.55
            return BoundedPan.offsetPx(align, window, extent) - BoundedPan.offsetPx(0.0, window, extent)
        }
        assertEquals(27.5, legacy(1.0), 1e-6)   // 2x: picture lagged far behind the finger
        assertEquals(192.5, legacy(3.0), 1e-6)  // 8x: picture overshot the finger
    }

    @Test fun edgesStayBounded() {
        val extent = BoundedPan.extentAtZoom(window, 0.0, 1.0)
        assertEquals(-1.0, BoundedPan.align(0.0, 5000.0, window, extent), 0.0)
        assertEquals(1.0, BoundedPan.align(0.0, -5000.0, window, extent), 0.0)
    }

    @Test fun axisThatFitsTheWindowNeverDrifts() {
        // e.g. 16:9 video on a near-square unfolded screen: vertical extent is smaller than the window.
        val extent = BoundedPan.extentAtZoom(1100.0, 0.0, 0.5)
        assertEquals(0.0, BoundedPan.align(0.4, 300.0, window, extent), 0.0)
    }

    @Test fun extentScalesWithZoomFromAnyMeasurement() {
        assertEquals(4000.0, BoundedPan.extentAtZoom(2000.0, 0.0, 1.0), 1e-9)
        assertEquals(2000.0, BoundedPan.extentAtZoom(4000.0, 1.0, 0.0), 1e-9)
    }
}
