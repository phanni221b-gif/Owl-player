package `is`.xyz.mpv

import java.security.MessageDigest

internal data class NeuralModel(val channels: Int, val file: String, val sha256: String) {
    fun verifies(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) } == sha256
    companion object {
        val neural = NeuralModel(8, "Owl-FSRCNNX-8.glsl", "452ef61fe190f46d4b208ab3c98046ad8b6e44637540ed3855875f07dcfcad7d")
        val aiMax = NeuralModel(16, "Owl-FSRCNNX-16.glsl", "02e4827369f45a79baf3e93fedba4859293a8fe4942902a3c34a6ce861b7284f")
        fun forMode(mode: QualityMode) = if (mode == QualityMode.AI_MAX) aiMax else neural
    }
}

internal data class RenderPassSummary(val neural8: Boolean, val neural16: Boolean, val passes: Int, val averageNs: Long) {
    companion object {
        fun parse(value: String?): RenderPassSummary? {
            val fields = value?.split(',')?.mapNotNull { part ->
                val pair = part.split('='); if (pair.size == 2) pair[0] to pair[1].toLongOrNull() else null
            }?.toMap() ?: return null
            return RenderPassSummary(fields["n8"] == 1L, fields["n16"] == 1L,
                fields["passes"]?.toInt() ?: return null, fields["avg_ns"] ?: return null)
        }
    }
}
