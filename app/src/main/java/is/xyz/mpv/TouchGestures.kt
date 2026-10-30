package `is`.xyz.mpv

import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import kotlin.math.*

enum class PropertyChange {
    Init,
    Seek,
    Volume,
    Bright,
    Finalize,

    /* Tap gestures */
    SeekFixed,
    PlayPause,
    Custom,
}

internal interface TouchGesturesObserver {
    fun onPropertyChange(p: PropertyChange, diff: Float)
}

internal class TouchGestures(private val observer: TouchGesturesObserver) {

    private enum class State {
        Up,
        Down,
        ControlSeek,
        ControlVolume,
        ControlBright,
    }

    private var state = State.Up
    private var stateDirection = 0

    private var lastTapTime = 0L
    private var lastDownTime = 0L

    private var initialPos = PointF()
    private var lastPos = PointF()

    private var width = 0f
    private var height = 0f
    private var trigger = 0f

    private var gestureHoriz = State.Down
    private var gestureVertLeft = State.Down
    private var gestureVertRight = State.Down
    private var tapGestureLeft : PropertyChange? = null
    private var tapGestureCenter : PropertyChange? = null
    private var tapGestureRight : PropertyChange? = null

    // Owl two-finger zoom / bounded pan. mpv video-zoom is log2 based:
    // 0 = fitted size, 1 = 2x, 2 = 4x, 3 = 8x.
    // Panning uses video-align instead of unrestricted video-pan so mpv itself keeps
    // the picture inside the legal video/window boundary.
    private var multiTouchActive = false
    private var suppressSingleTouchUntilUp = false
    private var lastPinchDistance = 0f
    private var lastPinchCenter = PointF()
    private var zoomValue = 0.0
    private var alignX = 0.0
    private var alignY = 0.0
    private var panResidualX = 0f
    private var panResidualY = 0f
    private var filteredPanX = 0f
    private var filteredPanY = 0f

    private inline fun checkFloat(vararg n: Float): Boolean {
        return !n.any { it.isInfinite() || it.isNaN() }
    }
    private inline fun assertFloat(vararg n: Float) {
        if (!checkFloat(*n))
            throw IllegalArgumentException()
    }

    fun setMetrics(width: Float, height: Float) {
        assertFloat(width, height)
        this.width = width
        this.height = height
        trigger = min(width, height) / TRIGGER_RATE
    }

    companion object {
        private const val TAG = "mpv"
        private const val TRIGGER_RATE = 30
        private const val TAP_DURATION = 300L
        private const val CONTROL_SEEK_MAX = 150f
        private const val CONTROL_VOLUME_MAX = 1.5f
        private const val CONTROL_BRIGHT_MAX = 1.5f
        private const val DEADZONE = 5
        private const val MAX_VIDEO_ZOOM = 3.0 // 8x
        private const val MIN_VIDEO_ZOOM = 0.0 // fitted size

        // Deliberately calmer than direct finger tracking.
        private const val PAN_SENSITIVITY = 0.45
        private const val PAN_JITTER_DEADZONE_FRACTION = 0.0035f
        private const val PAN_SMOOTHING_KEEP = 0.60f

        // Tiny changes in the distance between two resting fingers should not cause zoom wobble.
        private const val PINCH_SCALE_DEADZONE = 0.003
    }

    private fun processTap(p: PointF): Boolean {
        if (state == State.Up) {
            lastDownTime = SystemClock.uptimeMillis()
            if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() > trigger * 3)
                lastTapTime = 0
            return true
        }
        if (state != State.Down)
            return false

        val now = SystemClock.uptimeMillis()
        if (now - lastDownTime >= TAP_DURATION) {
            lastTapTime = 0
            return false
        }
        if (now - lastTapTime < TAP_DURATION) {
            if (p.x <= width * 0.28f)
                tapGestureLeft?.let { sendPropertyChange(it, -1f); return true }
            else if (p.x >= width * 0.72f)
                tapGestureRight?.let { sendPropertyChange(it, 1f); return true }
            else
                tapGestureCenter?.let { sendPropertyChange(it, 0f); return true }
            lastTapTime = 0
        } else {
            lastTapTime = now
        }
        return false
    }

    private fun processMovement(p: PointF): Boolean {
        if (PointF(lastPos.x - p.x, lastPos.y - p.y).length() < trigger / 3)
            return false
        lastPos.set(p)

        assertFloat(initialPos.x, initialPos.y)
        val dx = p.x - initialPos.x
        val dy = p.y - initialPos.y
        val dr = if (stateDirection == 0) (dx / width) else (-dy / height)

        when (state) {
            State.Up -> {}
            State.Down -> {
                if (abs(dx) > trigger) {
                    state = gestureHoriz
                    stateDirection = 0
                } else if (abs(dy) > trigger) {
                    state = if (initialPos.x > width / 2) gestureVertRight else gestureVertLeft
                    stateDirection = 1
                }
                if (state != State.Down)
                    sendPropertyChange(PropertyChange.Init, 0f)
            }
            State.ControlSeek ->
                sendPropertyChange(PropertyChange.Seek, CONTROL_SEEK_MAX * dr)
            State.ControlVolume ->
                sendPropertyChange(PropertyChange.Volume, CONTROL_VOLUME_MAX * dr)
            State.ControlBright ->
                sendPropertyChange(PropertyChange.Bright, CONTROL_BRIGHT_MAX * dr)
        }
        return state != State.Up && state != State.Down
    }

    private fun sendPropertyChange(p: PropertyChange, diff: Float) {
        observer.onPropertyChange(p, diff)
    }

    fun syncSettings(prefs: SharedPreferences, resources: Resources) {
        val get: (String, Int) -> String = { key, defaultRes ->
            val v = prefs.getString(key, "")
            if (v.isNullOrEmpty()) resources.getString(defaultRes) else v
        }
        val map = mapOf(
            "bright" to State.ControlBright,
            "seek" to State.ControlSeek,
            "volume" to State.ControlVolume
        )
        val map2 = mapOf(
            "seek" to PropertyChange.SeekFixed,
            "playpause" to PropertyChange.PlayPause,
            "custom" to PropertyChange.Custom
        )

        gestureHoriz = map[get("gesture_horiz", R.string.pref_gesture_horiz_default)] ?: State.Down
        gestureVertLeft = map[get("gesture_vert_left", R.string.pref_gesture_vert_left_default)] ?: State.Down
        gestureVertRight = map[get("gesture_vert_right", R.string.pref_gesture_vert_right_default)] ?: State.Down
        tapGestureLeft = map2[get("gesture_tap_left", R.string.pref_gesture_tap_left_default)]
        tapGestureCenter = map2[get("gesture_tap_center", R.string.pref_gesture_tap_center_default)]
        tapGestureRight = map2[get("gesture_tap_right", R.string.pref_gesture_tap_right_default)]
    }

    private fun pointerDistance(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return hypot(dx, dy)
    }

    private fun pointerCenter(e: MotionEvent): PointF {
        if (e.pointerCount < 2) return PointF(e.x, e.y)
        return PointF(
            (e.getX(0) + e.getX(1)) / 2f,
            (e.getY(0) + e.getY(1)) / 2f
        )
    }

    private fun clearPanFilter() {
        panResidualX = 0f
        panResidualY = 0f
        filteredPanX = 0f
        filteredPanY = 0f
    }

    private fun resetBoundedPan() {
        alignX = 0.0
        alignY = 0.0
        clearPanFilter()
        MPVLib.setPropertyDouble("video-align-x", 0.0)
        MPVLib.setPropertyDouble("video-align-y", 0.0)
        // Clear values left by older Owl builds so unrestricted video-pan cannot stack
        // with boundary-aware alignment and reveal empty space at the edge.
        MPVLib.setPropertyDouble("video-pan-x", 0.0)
        MPVLib.setPropertyDouble("video-pan-y", 0.0)
    }

    private fun beginMultiTouch(e: MotionEvent) {
        if (state != State.Up && state != State.Down)
            sendPropertyChange(PropertyChange.Finalize, 0f)
        state = State.Up
        lastTapTime = 0L
        multiTouchActive = true
        suppressSingleTouchUntilUp = true
        lastPinchDistance = pointerDistance(e).coerceAtLeast(1f)
        lastPinchCenter = pointerCenter(e)
        zoomValue = (MPVLib.getPropertyDouble("video-zoom") ?: 0.0)
            .coerceIn(MIN_VIDEO_ZOOM, MAX_VIDEO_ZOOM)
        alignX = (MPVLib.getPropertyDouble("video-align-x") ?: 0.0).coerceIn(-1.0, 1.0)
        alignY = (MPVLib.getPropertyDouble("video-align-y") ?: 0.0).coerceIn(-1.0, 1.0)
        clearPanFilter()
        MPVLib.setPropertyBoolean("video-recenter", true)
        MPVLib.setPropertyDouble("video-pan-x", 0.0)
        MPVLib.setPropertyDouble("video-pan-y", 0.0)
    }

    private fun processMultiTouch(e: MotionEvent): Boolean {
        if (e.pointerCount < 2) return true
        val distance = pointerDistance(e).coerceAtLeast(1f)
        val center = pointerCenter(e)

        val scaleFactor = (distance / lastPinchDistance).toDouble()
        if (scaleFactor.isFinite() && scaleFactor > 0.0 && abs(scaleFactor - 1.0) >= PINCH_SCALE_DEADZONE) {
            val deltaZoom = ln(scaleFactor) / ln(2.0)
            val newZoom = (zoomValue + deltaZoom).coerceIn(MIN_VIDEO_ZOOM, MAX_VIDEO_ZOOM)
            if (abs(newZoom - zoomValue) > 0.0005) {
                zoomValue = newZoom
                MPVLib.setPropertyDouble("video-zoom", zoomValue)
            }
        }

        if (zoomValue > 0.001) {
            val rawDx = center.x - lastPinchCenter.x
            val rawDy = center.y - lastPinchCenter.y
            panResidualX += rawDx
            panResidualY += rawDy

            // Ignore the center-point wobble made by two resting fingertips until it is
            // large enough to be intentional. Once accepted, run it through a light
            // low-pass filter to remove the remaining small frame-to-frame shake.
            val jitterDeadzone = max(2.5f, min(width, height) * PAN_JITTER_DEADZONE_FRACTION)
            if (hypot(panResidualX, panResidualY) >= jitterDeadzone) {
                filteredPanX = filteredPanX * PAN_SMOOTHING_KEEP +
                    panResidualX * (1f - PAN_SMOOTHING_KEEP)
                filteredPanY = filteredPanY * PAN_SMOOTHING_KEEP +
                    panResidualY * (1f - PAN_SMOOTHING_KEEP)

                val dx = if (width > 0f) filteredPanX / width else 0f
                val dy = if (height > 0f) filteredPanY / height else 0f

                // video-align is the inverse of direct manipulation: dragging the picture
                // right/down means showing more of its left/top side.
                alignX = (alignX - dx * PAN_SENSITIVITY).coerceIn(-1.0, 1.0)
                alignY = (alignY - dy * PAN_SENSITIVITY).coerceIn(-1.0, 1.0)
                MPVLib.setPropertyDouble("video-align-x", alignX)
                MPVLib.setPropertyDouble("video-align-y", alignY)

                panResidualX = 0f
                panResidualY = 0f
            }
        } else {
            // Returning to fitted size always recenters the picture.
            zoomValue = 0.0
            MPVLib.setPropertyDouble("video-zoom", 0.0)
            resetBoundedPan()
        }

        // Always use the current finger geometry as the next baseline. That prevents tiny
        // ignored pinch noise from accumulating into a sudden zoom jump during panning.
        lastPinchDistance = distance
        lastPinchCenter = center
        return true
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        if (width < 1 || height < 1) {
            Log.w(TAG, "TouchGestures: width or height not set!")
            return false
        }
        if (!checkFloat(e.x, e.y)) {
            Log.w(TAG, "TouchGestures: ignoring invalid point ${e.x} ${e.y}")
            return false
        }

        // Two fingers are reserved for Owl zoom/pan and never leak into the one-finger
        // brightness/volume/seek recognizer.
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    beginMultiTouch(e)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouchActive || e.pointerCount >= 2) {
                    if (!multiTouchActive) beginMultiTouch(e)
                    return processMultiTouch(e)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (multiTouchActive) {
                    if (e.pointerCount <= 2)
                        multiTouchActive = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                multiTouchActive = false
                suppressSingleTouchUntilUp = false
                clearPanFilter()
                state = State.Up
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (multiTouchActive || suppressSingleTouchUntilUp) {
                    multiTouchActive = false
                    suppressSingleTouchUntilUp = false
                    clearPanFilter()
                    state = State.Up
                    return true
                }
            }
        }

        var gestureHandled = false
        val point = PointF(e.x, e.y)
        when (e.action) {
            MotionEvent.ACTION_UP -> {
                gestureHandled = processMovement(point) or processTap(point)
                if (state != State.Down)
                    sendPropertyChange(PropertyChange.Finalize, 0f)
                state = State.Up
            }
            MotionEvent.ACTION_DOWN -> {
                if (e.y < height * DEADZONE / 100 || e.y > height * (100 - DEADZONE) / 100)
                    return false
                initialPos.set(point)
                processTap(point)
                lastPos.set(point)
                state = State.Down
                gestureHandled = true
            }
            MotionEvent.ACTION_MOVE -> {
                gestureHandled = processMovement(point)
            }
        }
        return gestureHandled
    }
}
