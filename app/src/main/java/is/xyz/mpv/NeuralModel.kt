package `is`.xyz.mpv

import java.security.MessageDigest

internal data class NeuralModel(val channels: Int, val file: String, val sha256: String) {
    fun verifies(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) } == sha256
    companion object {
        val neural = NeuralModel(8, "Owl-FSRCNNX-8.glsl", "078514ec7e7d6215c42db90c08e7d6f1208aa6d93cc372fa00c5fee1b24295c7")
        val aiMax = NeuralModel(16, "Owl-FSRCNNX-16.glsl", "f80259322a894df42da93fa04dda466557c427f61018fc69b46907a49d28d8a8")
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
