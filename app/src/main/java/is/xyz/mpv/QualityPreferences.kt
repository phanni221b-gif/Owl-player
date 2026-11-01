package `is`.xyz.mpv

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager

internal object QualityPreferences {
    const val NEURAL_ENABLED = "owl_neural_enabled"
    const val AI_ENABLED = "owl_ai_max_enabled"
    const val NEURAL_BUCKETS = "owl_neural_buckets"
    const val AI_BUCKETS = "owl_ai_max_buckets"
    fun buckets(prefs: SharedPreferences, ai: Boolean): Set<ResolutionBucket> {
        val defaults = if (ai) setOf("SD") else setOf("SD", "HD")
        return prefs.getStringSet(if (ai) AI_BUCKETS else NEURAL_BUCKETS, defaults).orEmpty()
            .mapNotNull { name -> ResolutionBucket.entries.find { it.name == name } }.toSet()
    }
    fun read(context: Context): QualityConfiguration {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val base = when (prefs.getString("apv_upscale_mode", "balanced")) {
            "sharp" -> QualityMode.SHARP; "ultra" -> QualityMode.ULTRA; else -> QualityMode.BALANCED
        }
        return QualityConfiguration(base,
            if (prefs.getBoolean(NEURAL_ENABLED, false)) buckets(prefs, false) else emptySet(),
            if (prefs.getBoolean(AI_ENABLED, false)) buckets(prefs, true) else emptySet())
    }
}
