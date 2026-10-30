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

        findViewById<CheckBox>(R.id.hardwareDecode).apply {
            isChecked = prefs.getBoolean("hardware_decoding", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("hardware_decoding", checked).apply()
            }
        }
    }

    companion object {
        private const val KEY_MODE = "apv_upscale_mode"
        private const val MODE_BALANCED = "balanced"
        private const val MODE_SHARP = "sharp"
        private const val MODE_ULTRA = "ultra"

        fun ensureDefaults(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!prefs.contains(KEY_MODE)) applyMode(context, MODE_BALANCED)
            if (!prefs.contains("hardware_decoding")) {
                prefs.edit().putBoolean("hardware_decoding", true).apply()
            }
            prefs.edit().putBoolean("video_fastdecode", false).apply()
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
