package `is`.xyz.mpv

/** Extracts a small, allowlisted token window from libplacebo's numbered shader dump.
 * Neither raw source nor arbitrary identifiers, comments, strings or paths are retained. */
internal class RendererSourceTrace {
    private var context: RendererAttemptContext? = null
    private var stage = RendererFailure.Stage.UNKNOWN
    private val targets = linkedSetOf<Int>()
    @Synchronized fun accept(attempt: RendererAttemptContext, text: String, failure: RendererFailure?): List<String> {
        if (attempt != context) { context = attempt; stage = RendererFailure.Stage.UNKNOWN; targets.clear() }
        failure?.line?.let { if (targets.size < 4) targets.add(it) }
        val clean = text.take(16_384).trim()
        when (clean) {
            "vertex shader source:" -> stage = RendererFailure.Stage.VERTEX
            "fragment shader source:" -> stage = RendererFailure.Stage.FRAGMENT
            "compute shader source:" -> stage = RendererFailure.Stage.COMPUTE
        }
        val result = mutableListOf<String>()
        for (line in clean.lineSequence().take(64)) {
            val match = sourceLine.matchEntire(line) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (stage == RendererFailure.Stage.UNKNOWN || targets.none { kotlin.math.abs(number - it) <= 2 }) continue
            result.add("generated_source stage=" + stage.name + ", line=" + number + ", tokens=" + tokens(match.groupValues[2]))
        }
        return result
    }
    companion object {
        private val sourceLine = Regex("^\\s*\\[\\s*([0-9]{1,6})]\\s?(.*)$")
        fun isSource(text: String) = text.trim() in setOf("vertex shader source:","fragment shader source:","compute shader source:") || sourceLine.matches(text.trimEnd())
        private val allowed = ("void bool float int uint vec2 vec3 vec4 ivec2 ivec3 ivec4 uvec2 uvec3 uvec4 mat2 mat3 mat4 " +
            "sampler2D samplerExternalOES sampler2DArray sampler3D sampler2DRect highp mediump lowp precision uniform in out inout const layout " +
            "return if else for while define version extension require enable disable main hook dot texture texture2D textureLod texelFetch clamp mix " +
            "fract floor ceil exp pow max min abs color luma neural res fcoord base index rgb rgba r g b a xy zw xyz xyzw " +
            "HOOKED_tex HOOKED_pos HOOKED_size HOOKED_pt OWL_LUMA_tex OWL_LUMA_texOff OWL_LUMA_pos OWL_SR_LUMA_tex OWL_SR_LUMA_pos " +
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
