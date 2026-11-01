package `is`.xyz.mpv

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

internal object QualityPlaybackOptions {
    fun options(mode: QualityMode, prefs: SharedPreferences): Map<String, String> {
        val values = QualityOptions.forMode(mode).toMutableMap()
        if (mode < QualityMode.ULTRA) {
            prefs.getString("video_scale", null)?.takeIf { it.isNotBlank() }?.let { values["scale"] = it }
            prefs.getString("video_downscale", null)?.takeIf { it.isNotBlank() }?.let { values["dscale"] = it }
            values["cscale"] = values.getValue("scale") // preserve pinned mpv's inherited chroma behavior
            values["deband"] = if (prefs.getString("video_debanding", "") == "gpu") "yes" else "no"
        }
        val fast = mode < QualityMode.ULTRA && prefs.getBoolean("video_fastdecode", false)
        values["vd-lavc-fast"] = if (fast) "yes" else "no"
        values["vd-lavc-skiploopfilter"] = if (fast) "nonkey" else "default"
        return values
    }
    fun initial(context: Context) {
        val configuration = QualityPreferences.read(context)
        if (!configuration.needsGpuNext) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        options(configuration.base, prefs).forEach { (key, value) -> MPVLib.setOptionString(key, value) }
    }
}
