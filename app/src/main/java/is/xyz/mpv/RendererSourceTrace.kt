package `is`.xyz.mpv

/** Extracts a small, allowlisted token window from libplacebo's numbered shader dump.
 * Neither raw source nor arbitrary identifiers, comments, strings or paths are retained. */
internal class RendererSourceTrace {
    private var context: RendererAttemptContext? = null
    private var stage = RendererFailure.Stage.UNKNOWN
    private val targets = linkedSetOf<Int>()
    private var preambleBudget = 0
    @Synchronized fun accept(attempt: RendererAttemptContext, text: String, failure: RendererFailure?): List<String> {
        if (attempt.source != context?.source || attempt.attempt != context?.attempt) {
            stage = RendererFailure.Stage.UNKNOWN
            targets.clear()
            preambleBudget = 0
        }
        context = attempt
        failure?.line?.let { if (targets.size < 4) targets.add(it) }
        val clean = text.take(16_384).trim()
        val result = mutableListOf<String>()
        for (line in clean.lineSequence().take(64)) {
            val header = headers[line.trim()]
            if (header != null) {
                stage = header
                preambleBudget = 12
                continue
            }
            val match = sourceLine.matchEntire(line) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (stage == RendererFailure.Stage.UNKNOWN) continue
            val body = match.groupValues[2].trim()
            val hook = when {
                body.startsWith("#define") -> null
                body.contains("dot(HOOKED_tex(HOOKED_pos).rgb") || body.contains("dot((HOOKED_mul * texture(HOOKED_raw, HOOKED_pos)).rgb") -> "main_rgb_to_luma"
                body.contains("OWL_SR_LUMA_tex(OWL_SR_LUMA_pos)") -> "main_rgb_reconstruction"
                body.contains("OWL_LUMA_texOff(") -> "cnn_first_feature"
                body.contains("SUBCONV1_tex(base)") -> "cnn_aggregation"
                else -> null
            }
            if (hook != null) result.add("generated_hook stage=" + stage.name + ", role=" + hook)
            val version = glslVersion.matchEntire(body)?.groupValues?.get(1)
            if (version != null && preambleBudget > 0) {
                result.add("generated_glsl stage=" + stage.name + ", version=" + version)
                preambleBudget--
            }
            val nearby = targets.any { kotlin.math.abs(number - it) <= 2 }
            val declaration = preambleBudget > 0 && samplerDeclaration.containsMatchIn(body)
            if (nearby || declaration) {
                result.add("generated_source stage=" + stage.name + ", line=" + number + ", tokens=" + tokens(body))
                if (declaration) preambleBudget--
            }
        }
        return result
    }
    companion object {
        private val sourceLine = Regex("^\\s*\\[\\s*([0-9]{1,6})]\\s?(.*)$")
        private val headers = mapOf("vertex shader source:" to RendererFailure.Stage.VERTEX,
            "fragment shader source:" to RendererFailure.Stage.FRAGMENT, "compute shader source:" to RendererFailure.Stage.COMPUTE)
        private val glslVersion = Regex("#version\\s+(100|300|310|320)(?:\\s+es)?")
        private val samplerDeclaration = Regex("^(?:precision|uniform|#extension)\\b.*(?:sampler|GL_OES_EGL_image_external)")
        fun isSource(text: String): Boolean {
            val first = text.lineSequence().firstOrNull()?.trim() ?: return false
            return first in headers || sourceLine.matches(first)
        }
        private val allowed = ("void bool float int uint vec2 vec3 vec4 ivec2 ivec3 ivec4 uvec2 uvec3 uvec4 mat2 mat3 mat4 " +
            "sampler2D samplerExternalOES sampler2DArray sampler3D sampler2DRect highp mediump lowp precision uniform in out inout const layout " +
            "return if else for while define version extension require enable disable main hook dot texture texture2D textureLod texelFetch clamp mix " +
            "fract floor ceil exp pow max min abs color luma neural res fcoord base index rgb rgba r g b a xy zw xyz xyzw " +
            "HOOKED_tex HOOKED_raw HOOKED_mul HOOKED_pos HOOKED_size HOOKED_pt OWL_LUMA_tex OWL_LUMA_texOff OWL_LUMA_pos OWL_SR_LUMA_tex OWL_SR_LUMA_pos " +
            "GL_OES_EGL_image_external GL_OES_EGL_image_external_essl3 GL_EXT_shader_io_blocks GL_EXT_texture_norm16 " +
            "common partition active asm class union enum typedef template this resource goto inline noinline public static extern external interface long short half fixed unsigned superp input output hvec2 hvec3 hvec4 fvec2 fvec3 fvec4 sampler3DRect filter sizeof cast namespace using row_major").split(' ').toSet()
        private val lex = Regex("[A-Za-z_][A-Za-z_0-9]*|[0-9]+(?:\\.[0-9]*)?|[^\\s]")
        fun tokens(source: String): String {
            val text = source.take(2048).substringBefore("//").replace(Regex("[\"'][^\"']*[\"']"), " ")
            if (text.trimStart().startsWith("#line") || text.contains("/*") || text.contains("*/")) return "redacted_directive_or_comment"
            return lex.findAll(text).take(96).map { m -> val t = m.value
                when { t in allowed -> t; t.matches(Regex("[0-9].*")) -> "N";
                    t.length == 1 && t[0] in "#(){}[];,.=+-*<>!&|?:%" -> t; else -> "_" }
            }.joinToString(" ")
        }
    }
}
