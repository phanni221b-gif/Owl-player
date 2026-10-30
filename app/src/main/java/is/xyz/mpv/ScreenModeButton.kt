package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import kotlin.math.max

/**
 * MX-style screen mode control for Owl playback.
 *
 * Modes:
 * - fit: preserve aspect ratio and show the whole frame
 * - fill: preserve aspect ratio and crop edges to fill the screen
 * - stretch: distort to the current window aspect ratio and fill everything
 */
class ScreenModeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    private enum class Mode(val label: String) {
        FIT("맞춤"),
        FILL("채움"),
        STRETCH("늘림")
    }

    private var mode = Mode.FIT

    init {
        text = mode.label
        setOnClickListener {
            mode = when (mode) {
                Mode.FIT -> Mode.FILL
                Mode.FILL -> Mode.STRETCH
                Mode.STRETCH -> Mode.FIT
            }
            applyMode()
        }
        setOnLongClickListener {
            showPicker()
            true
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (mode == Mode.STRETCH && w > 0 && h > 0) {
            post { applyMode(showToast = false) }
        }
    }

    private fun showPicker() {
        val labels = arrayOf(
            "맞춤 — 영상 전체 표시",
            "채움 — 비율 유지, 가장자리 잘라 꽉 채움",
            "늘림 — 화면 전체에 맞춰 늘림"
        )
        AlertDialog.Builder(context)
            .setTitle("화면 맞춤")
            .setSingleChoiceItems(labels, mode.ordinal) { dialog, which ->
                mode = Mode.values()[which]
                applyMode()
                dialog.dismiss()
            }
            .show()
    }

    private fun applyMode(showToast: Boolean = true) {
        // Any explicit screen mode change starts from a centered, non-pinch-zoomed image.
        MPVLib.setPropertyDouble("video-zoom", 0.0)
        MPVLib.setPropertyDouble("video-pan-x", 0.0)
        MPVLib.setPropertyDouble("video-pan-y", 0.0)

        when (mode) {
            Mode.FIT -> {
                MPVLib.setPropertyString("video-aspect-override", "-1")
                MPVLib.setPropertyDouble("panscan", 0.0)
            }
            Mode.FILL -> {
                MPVLib.setPropertyString("video-aspect-override", "-1")
                MPVLib.setPropertyDouble("panscan", 1.0)
            }
            Mode.STRETCH -> {
                MPVLib.setPropertyDouble("panscan", 0.0)
                val root = rootView
                val width = max(1, root.width)
                val height = max(1, root.height)
                val ratio = width.toDouble() / height.toDouble()
                MPVLib.setPropertyString("video-aspect-override", ratio.toString())
            }
        }
        text = mode.label
        if (showToast) {
            android.widget.Toast.makeText(context, "화면: ${mode.label}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
