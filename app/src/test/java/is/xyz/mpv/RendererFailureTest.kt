package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test

class RendererFailureTest {
    private fun error(text: String) = RendererFailure.classify("vo/gpu-next/libplacebo",20,text)!!
    @Test fun adrenoCompilerLinePreservesOnlySafeDetails() {
        val e = error("ERROR: 0:109: S0001: No precision specified for sampler2D")
        assertEquals(RendererFailure.Category.SHADER_COMPILE_ERROR,e.category)
        assertEquals(RendererFailure.Reason.SAMPLER_PRECISION_MISSING,e.reason)
        assertEquals(109,e.line); assertEquals("S0001",e.compilerCode)
        assertEquals(RendererFailure.Symbol.SAMPLER2D,e.symbol)
    }
    @Test fun compilerLinesWithoutShaderKeywordAreNotLost() {
        val e = error("0:72: S0001: syntax error")
        assertEquals(RendererFailure.Category.SHADER_COMPILE_ERROR,e.category)
        assertEquals(72,e.line); assertEquals("S0001",e.compilerCode)
    }
    @Test fun categoriesDistinguishResourceFailuresAndShaderStages() {
        val cases = mapOf(
            "Failed compiling fragment shader" to RendererFailure.Category.SHADER_COMPILE_ERROR,
            "Failed linking shader program" to RendererFailure.Category.SHADER_LINK_ERROR,
            "Failed dispatching compute shader" to RendererFailure.Category.SHADER_DISPATCH_ERROR,
            "Failed allocating render target" to RendererFailure.Category.RENDER_TARGET_ERROR,
            "Framebuffer incomplete" to RendererFailure.Category.FRAMEBUFFER_ERROR,
            "Texture format rgba16f is unsupported" to RendererFailure.Category.TEXTURE_FORMAT_ERROR,
            "Too many temporary registers" to RendererFailure.Category.GPU_RESOURCE_LIMIT,
            "Too many texture image units" to RendererFailure.Category.GPU_RESOURCE_LIMIT,
            "OpenGL error: GL_INVALID_OPERATION" to RendererFailure.Category.GL_INVALID_OPERATION,
            "Failed rendering frame" to RendererFailure.Category.SHADER_DISPATCH_ERROR)
        for ((message,category) in cases) assertEquals(message,category,error(message).category)
        assertEquals(RendererFailure.Stage.FRAGMENT,error("Failed compiling fragment shader").stage)
        assertEquals(RendererFailure.Stage.COMPUTE,error("Failed dispatching compute shader").stage)
        assertEquals(RendererFailure.Reason.OUT_OF_MEMORY,error("Failed allocating texture GL_OUT_OF_MEMORY").reason)
    }
    @Test fun glEnumAndAllowlistedSymbolsAreTyped() {
        assertEquals(RendererFailure.GlError.GL_INVALID_OPERATION,error("OpenGL error: 0x0502").glError)
        assertEquals(RendererFailure.GlError.GL_INVALID_OPERATION,error("gl_tex_create: OpenGL error: invalid operation").glError)
        assertEquals(RendererFailure.Symbol.SAMPLER2DARRAY,error("Failed compiling shader sampler2DArray").symbol)
    }
    @Test fun nonRendererWarningsAndSuccessfulMessagesDoNotTriggerFallback() {
        assertNull(RendererFailure.classify("demux",20,"shader failed in /private/name"))
        assertNull(RendererFailure.classify("vo/gpu-next/libplacebo",30,"shader compiler warning: unused variable"))
        assertNull(RendererFailure.classify("vo/gpu-next/libplacebo",30,"WARNING: 0:81: unused variable"))
        assertNull(RendererFailure.classify("vo/gpu-next/libplacebo",40,"shader compiled successfully"))
    }
    @Test fun rawPathsUrlsAndFilenamesCannotEscapeClassification() {
        val secrets = listOf("/storage/ABCD/Family/private-film.mp4", "content://media/external/video/987",
            "C:" + 92.toChar() + "Users" + 92.toChar() + "PrivatePerson" + 92.toChar() + "video.mp4",
            "https://secret.example/user-token/movie.mkv")
        for (secret in secrets) {
            val result = error("Failed compiling fragment shader for "+secret+"\nERROR: 0:12: S0001: syntax error").summary()
            assertFalse(result.contains(secret)); assertFalse(result.contains("PrivatePerson")); assertFalse(result.contains("private-film"))
            assertFalse(result.contains("user-token")); assertFalse(result.contains("/")); assertFalse(result.contains(92.toChar()))
        }
    }
    @Test fun aFilenameNumberIsNotReportedAsACompilerLocation() {
        val e = error("Failed opening shader /storage/user/line123-S0001.glsl")
        assertNull(e.line); assertNull(e.compilerCode)
    }
    @Test fun detailedCompilerLineOutranksGenericFailureForTheSameAttempt() {
        assertTrue(error("ERROR: 0:15: S0001: No precision specified for sampler2D").detailScore > error("Failed rendering frame").detailScore)
    }
    @Test fun secondaryPassFailureDoesNotInventALinkFailure() {
        val build = error("Failed compiling/linking GLSL program")
        val pass = error("Failed creating render pass for dispatch")
        val compile = error("shader compile log (status=0): ERROR: 0:81: syntax error")
        val link = error("shader link log (status=0): shader failed to link")
        assertEquals(RendererFailure.Reason.PROGRAM_BUILD_FAILED, build.reason)
        assertEquals(RendererFailure.Reason.PASS_CREATION_FAILED, pass.reason)
        assertEquals(RendererFailure.Category.SHADER_LINK_ERROR, link.category)
        assertTrue(compile.detailScore > link.detailScore && link.detailScore > build.detailScore)
        assertNull(compile.compilerCode)
    }
}
