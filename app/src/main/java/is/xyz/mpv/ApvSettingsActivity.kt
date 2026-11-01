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

        findViewById<CheckBox>(R.id.diagnosticsEnabled).apply {
            isChecked = OwlDiagnostics.enabled(this@ApvSettingsActivity)
            setOnCheckedChangeListener { _, checked -> OwlDiagnostics.setEnabled(this@ApvSettingsActivity, checked) }
        }
        findViewById<android.view.View>(R.id.diagnosticsReport).setOnClickListener { OwlDiagnostics.showReport(this) }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        findViewById<ImageButton>(R.id.settingsBackButton).setOnClickListener { finish() }

        findViewById<CheckBox>(R.id.resumePlayback).apply {
            isChecked = prefs.getBoolean(KEY_RESUME, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(KEY_RESUME, checked).apply()
            }
        }

        findViewById<CheckBox>(R.id.videoAutoRotate).apply {
            isChecked = prefs.getString("auto_rotation", "auto") == "auto"
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putString("auto_rotation", if (checked) "auto" else "device").apply()
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

        configureNeuralRow(false, prefs)
        configureNeuralRow(true, prefs)
        findViewById<android.view.View>(R.id.neuralAttribution).setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle("FSRCNNX · GPU")
                .setMessage(assets.open("neural/ATTRIBUTION.txt").bufferedReader().use { it.readText() })
                .setPositiveButton("닫기", null)
                .setNeutralButton("LGPLv3 / GPLv3") { _, _ ->
                    val text = listOf("COPYING.LESSER", "COPYING").joinToString("\n\n") { file ->
                        assets.open("neural/" + file).bufferedReader().use { it.readText() }
                    }
                    android.app.AlertDialog.Builder(this).setTitle("LGPL-3.0-or-later")
                        .setMessage(text).setPositiveButton("닫기", null).show()
                }.show()
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

    private fun configureNeuralRow(ai: Boolean, prefs: android.content.SharedPreferences) {
        val enabledKey = if (ai) QualityPreferences.AI_ENABLED else QualityPreferences.NEURAL_ENABLED
        val bucketKey = if (ai) QualityPreferences.AI_BUCKETS else QualityPreferences.NEURAL_BUCKETS
        val check = findViewById<CheckBox>(if (ai) R.id.aiMaxEnabled else R.id.neuralEnabled)
        val label = if (ai) "AI Max" else "Neural"
        fun updateLabel() {
            check.text = label + " · GPU\n" + QualityPreferences.buckets(prefs, ai).joinToString { it.label }.ifEmpty { "적용 해상도를 선택해주세요" }
        }
        updateLabel()
        check.isChecked = prefs.getBoolean(enabledKey, false)
        check.setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean(enabledKey, enabled).apply() }
        findViewById<android.view.View>(if (ai) R.id.aiMaxResolutionSettings else R.id.neuralResolutionSettings).setOnClickListener {
            val buckets = ResolutionBucket.entries
            val selected = QualityPreferences.buckets(prefs, ai)
            val checked = buckets.map { it in selected }.toBooleanArray()
            android.app.AlertDialog.Builder(this).setTitle(label + " · 원본 영상 해상도")
                .setMultiChoiceItems(buckets.map { it.label }.toTypedArray(), checked) { _, which, value -> checked[which] = value }
                .setPositiveButton("적용") { _, _ ->
                    prefs.edit().putStringSet(bucketKey, buckets.filterIndexed { index, _ -> checked[index] }.map { it.name }.toSet()).apply()
                    updateLabel()
                }.setNegativeButton("취소", null).show()
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
            val defaults = mapOf<String, Any>(
                KEY_MODE to MODE_BALANCED, "video_scale" to "bicubic", "video_downscale" to "bilinear",
                "video_debanding" to "", "gpu_next" to false, "video_fastdecode" to false,
                KEY_DECODER_MODE to DECODER_AUTO, "hardware_decoding" to true, KEY_RESUME to true,
                "auto_rotation" to "auto", "display_media_title" to true, "bottom_controls" to true,
                "playlist_exit_warning" to false, "gesture_horiz" to "seek", "gesture_vert_left" to "bright",
                "gesture_vert_right" to "volume", "gesture_tap_left" to "seek",
                "gesture_tap_center" to "playpause", "gesture_tap_right" to "seek"
            )
            val missing = MediaRules.missingDefaults(prefs.all, defaults)
            if (missing.isEmpty()) return
            prefs.edit().also { editor -> missing.forEach { (key, value) ->
                when (value) { is Boolean -> editor.putBoolean(key, value); is String -> editor.putString(key, value) }
            } }.apply()
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
