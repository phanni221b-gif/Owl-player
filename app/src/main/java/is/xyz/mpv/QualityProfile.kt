package `is`.xyz.mpv

import kotlin.math.max
import kotlin.math.min

enum class QualityMode(val label: String) {
    BALANCED("Balanced"), SHARP("Sharp"), ULTRA("Ultra"), NEURAL("Neural"), AI_MAX("AI Max");
    val neural get() = this == NEURAL || this == AI_MAX
}

enum class ResolutionBucket(val label: String, val shortEdge: Int, val longEdge: Int) {
    SD("480p 이하", 480, 854), HD("720p", 720, 1280), FHD("1080p", 1080, 1920),
    QHD("1440p", 1440, 2560), UHD("4K급 이상 · 1440p 초과", Int.MAX_VALUE, Int.MAX_VALUE);
    companion object {
        /** Smallest enclosing 16:9 reference box, independent of portrait/landscape orientation. */
        fun classify(width: Int, height: Int): ResolutionBucket? {
            if (width <= 0 || height <= 0) return null
            val shorter = min(width, height); val longer = max(width, height)
            return entries.first { shorter <= it.shortEdge && longer <= it.longEdge }
        }
    }
}

data class SourceVideo(val width: Int, val height: Int, val fps: Double?, val hdr: Boolean?, val codec: String) {
    val bucket get() = ResolutionBucket.classify(width, height)
}

data class QualityConfiguration(val base: QualityMode = QualityMode.BALANCED,
    val neural: Set<ResolutionBucket> = emptySet(), val aiMax: Set<ResolutionBucket> = emptySet()) {
    init { require(!base.neural) }
    val needsGpuNext get() = base == QualityMode.ULTRA || neural.isNotEmpty() || aiMax.isNotEmpty()
    fun candidates(source: SourceVideo): List<QualityMode> {
        val requested = when (source.bucket) {
            in aiMax -> listOf(QualityMode.AI_MAX, QualityMode.NEURAL, QualityMode.ULTRA, base)
            in neural -> listOf(QualityMode.NEURAL, QualityMode.ULTRA, base)
            else -> listOf(base)
        }
        return (requested + QualityMode.BALANCED).distinct()
    }
}

/** Independent policy seam for measured device limits; no per-frame Kotlin quality decision. */
data class QualityPolicy(val allowHdrNeural: Boolean = false, val maxAiFps: Double? = null,
    val thermalRestricted: Boolean = false, val batterySaver: Boolean = false) {
    fun rejection(mode: QualityMode, source: SourceVideo, gpuNextAvailable: Boolean = true): String? = when {
        mode >= QualityMode.ULTRA && !gpuNextAvailable -> "gpu_next_unavailable"
        !mode.neural -> null
        source.bucket == null -> "source_dimensions_unavailable"
        !allowHdrNeural && source.hdr == null -> "source_transfer_unavailable"
        !allowHdrNeural && source.hdr == true -> "model_validated_for_sdr_only"
        thermalRestricted -> "thermal_policy"
        batterySaver -> "battery_saver_policy"
        mode == QualityMode.AI_MAX && maxAiFps != null && (source.fps ?: 0.0) > maxAiFps -> "fps_policy"
        else -> null
    }
}

/** A failed candidate is never retried within this source generation. */
internal class QualityFallback(val candidates: List<QualityMode>) {
    private var index = 0
    val reasons = mutableListOf<String>()
    val current get() = candidates.getOrNull(index)
    fun fail(reason: String): QualityMode? {
        current?.let { reasons.add(it.label + ": " + reason) }
        index++
        return current
    }
}

internal object QualityOptions {
    // Verified in pinned mpv 8e5a96fc etc/builtin.conf and DOCS/man/options.rst.
    fun forMode(mode: QualityMode): Map<String, String> {
        val high = mode >= QualityMode.ULTRA
        return linkedMapOf(
            "scale" to when { high -> "ewa_lanczossharp"; mode == QualityMode.SHARP -> "spline36"; else -> "bicubic" },
            "dscale" to if (mode == QualityMode.BALANCED) "bilinear" else "mitchell",
            "cscale" to if (high || mode == QualityMode.SHARP) "spline36" else "bicubic",
            "deband" to if (high) "yes" else "no",
            "dither" to if (high) "fruit" else "no", "dither-depth" to "auto",
            "correct-downscaling" to if (high) "yes" else "no",
            "linear-downscaling" to if (high) "yes" else "no",
            "sigmoid-upscaling" to if (high) "yes" else "no",
            "hdr-compute-peak" to if (high) "auto" else "no",
            "allow-delayed-peak-detect" to if (high) "no" else "yes",
            "tone-mapping" to "auto", "fbo-format" to "auto",
            "scale-antiring" to if (high) "0.6" else "0.0"
        )
    }
}

internal object NeuralGeometry {
    /** Same strict 1.3x threshold as the bundled shader, invariant under 90-degree rotation. */
    fun upscaleRequired(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int): Boolean? {
        if (minOf(sourceWidth, sourceHeight, outputWidth, outputHeight) <= 0) return null
        return minOf(outputWidth, outputHeight).toDouble() / minOf(sourceWidth, sourceHeight) > 1.3 &&
            maxOf(outputWidth, outputHeight).toDouble() / maxOf(sourceWidth, sourceHeight) > 1.3
    }
}
