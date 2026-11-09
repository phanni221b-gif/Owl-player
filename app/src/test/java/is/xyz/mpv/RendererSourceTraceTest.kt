package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test

class RendererSourceTraceTest {
    private val context = RendererAttemptContext(1,2,QualityMode.NEURAL,RendererAttemptPhase.RENDER_CONFIRM)
    private fun error(text: String) = RendererFailure.classify("vo/gpu-next/libplacebo",20,text)!!
    @Test fun sourceDumpsAreNotRendererFailures() {
        for (text in listOf("fragment shader source:", "[ 81] vec4 color = texture(sampler2D(0), vec2(1));", "[  2] precision highp float;")) {
            assertTrue(RendererSourceTrace.isSource(text))
            assertNull(RendererFailure.classify("vo/gpu-next/libplacebo",20,text))
        }
    }
    @Test fun onlyBoundedCompilerWindowIsCapturedWithRealStage() {
        val trace = RendererSourceTrace()
        trace.accept(context,"ERROR: 0:81: syntax error",error("ERROR: 0:81: syntax error"))
        trace.accept(context,"fragment shader source:",null)
        assertTrue(trace.accept(context,"[ 78] float neural;",null).isEmpty())
        val line = trace.accept(context,"[ 81] float neural = dot(color.rgb, vec3(1));",null).single()
        assertTrue(line.contains("stage=FRAGMENT, line=81")); assertTrue(line.contains("float neural = dot"))
        assertTrue(trace.accept(context,"[ 84] float neural;",null).isEmpty())
    }
    @Test fun noSourceIsRecordedWithoutCompilerFailure() {
        val trace = RendererSourceTrace()
        trace.accept(context,"fragment shader source:",null)
        assertTrue(trace.accept(context,"[ 81] float neural;",null).isEmpty())
    }
    @Test fun generatedEvidenceSurvivesSubmissionPhaseChange() {
        val trace = RendererSourceTrace()
        val submitted = context.copy(phase = RendererAttemptPhase.SHADER_SUBMITTED)
        trace.accept(submitted, "", error("ERROR: 0:81: syntax error"))
        val lines = trace.accept(context, """
            fragment shader source:
            [  1] #version 310 es
            [  2] uniform highp samplerExternalOES private_name;
            [ 81] vec4 hook() { return vec4(dot(HOOKED_tex(HOOKED_pos).rgb, vec3(1)), 0, 0, 1); }
        """.trimIndent(), null)
        assertTrue(lines.any { it.contains("version=310") })
        assertTrue(lines.any { it.contains("samplerExternalOES") })
        assertTrue(lines.any { it.contains("role=main_rgb_to_luma") })
        assertTrue(lines.any { it.contains("line=81") })
        assertFalse(lines.any { it.contains("private_name") })
    }
    @Test fun attemptsDoNotShareCompilerTargetsOrShaderStage() {
        val trace = RendererSourceTrace()
        trace.accept(context,"",error("ERROR: 0:81: syntax error"))
        trace.accept(context,"fragment shader source:",null)
        assertTrue(trace.accept(context.copy(attempt=3),"[ 81] float neural;",null).isEmpty())
    }
    @Test fun privateIdentifiersStringsCommentsAndDirectivesCannotEscape() {
        for (secret in listOf("PrivatePerson", "secret_movie", "user_token")) {
            val safe = RendererSourceTrace.tokens("vec4 "+secret+"; // /storage/"+secret+"/movie.mp4")
            assertFalse(safe.contains(secret)); assertFalse(safe.contains("storage")); assertFalse(safe.contains("movie"))
        }
        assertEquals("redacted_directive_or_comment",RendererSourceTrace.tokens("#line 81 /storage/PrivatePerson/movie"))
        assertFalse(RendererSourceTrace.tokens("#define value "+34.toChar()+"private-file"+34.toChar()).contains("private-file"))
    }
    @Test fun sourceTokensHaveABoundedSize() {
        assertTrue(RendererSourceTrace.tokens("float color; ".repeat(10000)).length < 1000)
    }
    @Test fun actualCompilerTermsSurviveWithoutRawMessages() {
        val e = error("shader compile log (status=0): ERROR: 0:81: syntax error, unexpected identifier, expecting semicolon /private/movie")
        assertEquals(81,e.line)
        assertTrue(e.terms.containsAll(setOf(RendererFailure.Term.SYNTAX_ERROR,RendererFailure.Term.UNEXPECTED_TOKEN,RendererFailure.Term.EXPECTED_TOKEN)))
        assertFalse(e.summary().contains("private"))
        assertTrue(error("ERROR: 0:81: reserved word").terms.contains(RendererFailure.Term.RESERVED_WORD))
        assertTrue(error("ERROR: 0:81: no matching overloaded function found").terms.contains(RendererFailure.Term.NO_MATCHING_OVERLOAD))
    }
    @Test fun meaninglessErrorLevelDumpTextCannotDowngradePlayback() {
        assertNull(RendererFailure.classify("vo/gpu-next/libplacebo",20,"    precision highp float;"))
        assertNull(RendererFailure.classify("vo/gpu-next/libplacebo",20,"   vec4 color = texture(sampler, position);"))
    }
}
