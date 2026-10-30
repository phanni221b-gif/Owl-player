package `is`.xyz.mpv

import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class ApvSettingsActivity : AppCompatActivity(R.layout.activity_apv_settings) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarInsets.apply(findViewById(R.id.settingsRoot))

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        findViewById<ImageButton>(R.id.settingsBackButton).setOnClickListener { finish() }

        findViewById<CheckBox>(R.id.resumePlayback).apply {
            isChecked = prefs.getBoolean(KEY_RESUME, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_RESUME, checked).apply()
            }
        }

        findViewById<CheckBox>(R.id.mxGestures).apply {
            isChecked = isMxGesturesEnabled(this@ApvSettingsActivity)
            setOnCheckedChangeListener { _, checked -> applyMxGestures(this@ApvSettingsActivity, checked) }
        }

        val modes = findViewById<RadioGroup>(R.id.upscaleModes)
        when (prefs.getString(KEY_MODE, MODE_BALANCED)) {
            MODE_SHARP -> modes.check(R.id.upscaleSharp)
            MODE_ULTRA -> modes.check(R.id.upscaleUltra)
            else -> modes.check(R.id.upscaleBalanced)
        }
        modes.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.upscaleSharp -> MODE_SHARP
                R.id.upscaleUltra -> MODE_ULTRA
                else -> MODE_BALANCED
            }
            applyMode(this, mode)
        }

        val decoders = findViewById<RadioGroup>(R.id.decoderModes)
        when (prefs.getString(KEY_DECODER_MODE, DECODER_AUTO)) {
            DECODER_HW_PLUS -> decoders.check(R.id.decoderHwPlus)
            DECODER_HW -> decoders.check(R.id.decoderHw)
            DECODER_SW -> decoders.check(R.id.decoderSw)
            else -> decoders.check(R.id.decoderAuto)
        }
        decoders.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.decoderHwPlus -> DECODER_HW_PLUS
                R.id.decoderHw -> DECODER_HW
                R.id.decoderSw -> DECODER_SW
                else -> DECODER_AUTO
            }
            prefs.edit()
                .putString(KEY_DECODER_MODE, mode)
                .putBoolean("hardware_decoding", mode != DECODER_SW)
                .apply()
        }
    }

    companion object {
        private const val KEY_MODE = "apv_upscale_mode"
        private const val KEY_RESUME = "owl_resume_playback"
        const val KEY_DECODER_MODE = "owl_decoder_mode"

        private const val MODE_BALANCED = "balanced"
        private const val MODE_SHARP = "sharp"
        private const val MODE_ULTRA = "ultra"

        const val DECODER_AUTO = "auto"
        const val DECODER_HW_PLUS = "hw_plus"
        const val DECODER_HW = "hw"
        const val DECODER_SW = "sw"

        fun ensureDefaults(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.contains(KEY_MODE)) applyMode(context, MODE_BALANCED)

            val edit = prefs.edit()
            if (!prefs.contains(KEY_DECODER_MODE)) edit.putString(KEY_DECODER_MODE, DECODER_AUTO)
            if (!prefs.contains("hardware_decoding")) edit.putBoolean("hardware_decoding", true)
            if (!prefs.contains(KEY_RESUME)) edit.putBoolean(KEY_RESUME, true)
            if (!prefs.contains("display_media_title")) edit.putBoolean("display_media_title", true)
            if (!prefs.contains("bottom_controls")) edit.putBoolean("bottom_controls", true)
            if (!prefs.contains("playlist_exit_warning")) edit.putBoolean("playlist_exit_warning", false)
            edit.putBoolean("video_fastdecode", false).apply()

            if (!prefs.contains("gesture_tap_left") ||
                !prefs.contains("gesture_tap_center") ||
                !prefs.contains("gesture_tap_right")) {
                applyMxGestures(context, true)
            }
        }

        fun isResumeEnabled(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_RESUME, true)

        private fun isMxGesturesEnabled(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getString("gesture_horiz", "seek") == "seek" &&
                prefs.getString("gesture_vert_left", "bright") == "bright" &&
                prefs.getString("gesture_vert_right", "volume") == "volume" &&
                prefs.getString("gesture_tap_left", "seek") == "seek" &&
                prefs.getString("gesture_tap_center", "playpause") == "playpause" &&
                prefs.getString("gesture_tap_right", "seek") == "seek"
        }

        private fun applyMxGestures(context: Context, enabled: Boolean) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (enabled) {
                prefs.edit()
                    .putString("gesture_horiz", "seek")
                    .putString("gesture_vert_left", "bright")
                    .putString("gesture_vert_right", "volume")
                    .putString("gesture_tap_left", "seek")
                    .putString("gesture_tap_center", "playpause")
                    .putString("gesture_tap_right", "seek")
                    .apply()
            } else {
                prefs.edit()
                    .putString("gesture_horiz", "none")
                    .putString("gesture_vert_left", "none")
                    .putString("gesture_vert_right", "none")
                    .putString("gesture_tap_left", "none")
                    .putString("gesture_tap_center", "none")
                    .putString("gesture_tap_right", "none")
                    .apply()
            }
        }

        private fun applyMode(context: Context, mode: String) {
            val edit = PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(KEY_MODE, mode)
                .putBoolean("video_fastdecode", false)

            when (mode) {
                MODE_SHARP -> edit
                    .putString("video_scale", "spline36")
                    .putString("video_downscale", "mitchell")
                    .putString("video_debanding", "")
                    .putBoolean("gpu_next", false)
                MODE_ULTRA -> edit
                    .putString("video_scale", "ewa_lanczossharp")
                    .putString("video_downscale", "mitchell")
                    .putString("video_debanding", "gpu")
                    .putBoolean("gpu_next", true)
                else -> edit
                    .putString("video_scale", "bicubic")
                    .putString("video_downscale", "bilinear")
                    .putString("video_debanding", "")
                    .putBoolean("gpu_next", false)
            }
            edit.apply()
        }
    }
}
