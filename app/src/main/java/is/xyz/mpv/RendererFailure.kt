package `is`.xyz.mpv

/** Only enums, bounded numbers and allowlisted compiler tokens survive classification. Never stores input text. */
internal data class RendererFailure(
    val category: Category, val reason: Reason, val stage: Stage = Stage.UNKNOWN,
    val line: Int? = null, val compilerCode: String? = null, val glError: GlError? = null,
    val symbol: Symbol? = null, val terms: Set<Term> = emptySet()
) {
    enum class Category { SHADER_COMPILE_ERROR, SHADER_LINK_ERROR, SHADER_DISPATCH_ERROR, SHADER_LOAD_ERROR,
        RENDER_TARGET_ERROR, FRAMEBUFFER_ERROR, TEXTURE_FORMAT_ERROR, GPU_RESOURCE_LIMIT,
        LIBPLACEBO_ERROR, GL_INVALID_OPERATION, UNKNOWN_RENDERER_SHADER_ERROR }
    enum class Reason { COMPILE_FAILED, LINK_FAILED, DISPATCH_FAILED, FILE_OPEN_FAILED, TARGET_ALLOCATION_FAILED,
        FBO_INCOMPLETE, FORMAT_UNSUPPORTED, TEMPORARY_REGISTER_LIMIT, INSTRUCTION_LIMIT, SAMPLER_LIMIT,
        OUT_OF_MEMORY, RESOURCE_LIMIT, SAMPLER_PRECISION_MISSING, FLOAT_PRECISION_MISSING, PRECISION_MISMATCH,
        EXTENSION_UNSUPPORTED, SYNTAX_OR_TYPE_ERROR, INVALID_OPERATION, DRIVER_REPORTED_ERROR, RENDERER_REPORTED_ERROR }
    enum class Stage { UNKNOWN, VERTEX, FRAGMENT, COMPUTE }
    enum class GlError { GL_INVALID_ENUM, GL_INVALID_VALUE, GL_INVALID_OPERATION, GL_OUT_OF_MEMORY, GL_INVALID_FRAMEBUFFER_OPERATION }
    enum class Symbol { SAMPLER2D, SAMPLEREXTERNALOES, SAMPLER2DARRAY, MAT4X4, TEXTURE2D, PRECISION }
    enum class Term { RESERVED_WORD, UNDECLARED_IDENTIFIER, NO_MATCHING_OVERLOAD, TYPE_MISMATCH, UNEXPECTED_TOKEN, EXPECTED_TOKEN, REDEFINITION, READ_ONLY, ARRAY_INDEX, SWIZZLE, SYNTAX_ERROR }
    fun fixedCode() = category.name.lowercase() + ":" + reason.name.lowercase()
    fun summary() = fixedCode() + ", stage=" + stage.name.lowercase() + ", line=" + (line ?: "unavailable") +
        ", compiler=" + (compilerCode ?: "unavailable") + ", gl=" + (glError?.name ?: "unavailable") + ", symbol=" + (symbol?.name ?: "unavailable") + ", terms=" + terms.joinToString("|") { it.name }
    val detailScore get() = (if (category == Category.UNKNOWN_RENDERER_SHADER_ERROR || category == Category.LIBPLACEBO_ERROR) 0 else 10) +
        (if (line != null) 2 else 0) + (if (compilerCode != null) 3 else 0) +
        (if (reason in setOf(Reason.SAMPLER_PRECISION_MISSING,Reason.FLOAT_PRECISION_MISSING,Reason.TEMPORARY_REGISTER_LIMIT,Reason.SAMPLER_LIMIT)) 5 else 0)
    companion object {
        private val compilerLocation = Regex("(?im)^(?:\\s*shader compile log \\(status=0\\):)?\\s*(?:ERROR|WARNING)\\s*:\\s*\\d{1,5}\\s*:\\s*(\\d{1,6})\\s*:")
        private val adrenoLocation = Regex("(?im)^\\s*\\d{1,5}\\s*:\\s*(\\d{1,6})\\s*:\\s*[SC]\\d{4}\\b")
        private val compilerNumber = Regex("(?i)(?:\\b(?:error|warning)\\s*:?\\s*|^\\s*\\d{1,5}:\\d{1,6}:\\s*)([SC]\\d{4})\\b")
        fun classify(prefix: String, level: Int, message: String): RendererFailure? {
            if (level > 30 || level <= 0) return null
            val origin = prefix.lowercase()
            if (!origin.contains("gpu") && !origin.contains("libplacebo") && !origin.contains("opengl")) return null
            if (RendererSourceTrace.isSource(message)) return null
            val text = message.take(16_384)
            val low = text.lowercase()
            val location = compilerLocation.find(text) ?: adrenoLocation.find(text)
            val code = compilerNumber.find(text)?.groupValues?.get(1)?.uppercase()
                ?: if (location != null) Regex("[SC][0-9]{4}",RegexOption.IGNORE_CASE).find(text)?.value?.uppercase() else null
            if (level > 20 && !listOf("failed", "error", "unsupported", "not supported", "incomplete").any { low.contains(it) } && code == null) return null
            val stage = when { low.contains("fragment") -> Stage.FRAGMENT; low.contains("vertex") -> Stage.VERTEX; low.contains("compute") -> Stage.COMPUTE; else -> Stage.UNKNOWN }
            val gl = GlError.entries.firstOrNull { low.contains(it.name.lowercase()) } ?: when {
                low.contains("opengl error: invalid operation") -> GlError.GL_INVALID_OPERATION
                low.contains("opengl error: invalid enum") -> GlError.GL_INVALID_ENUM
                low.contains("opengl error: invalid value") -> GlError.GL_INVALID_VALUE
                low.contains("opengl error: out of memory") -> GlError.GL_OUT_OF_MEMORY
                low.contains("opengl error: invalid framebuffer operation") -> GlError.GL_INVALID_FRAMEBUFFER_OPERATION
                Regex("(?i)(?:gl(?:_error)?|opengl error)\\s*[:=]?\\s*0x0500\\b").containsMatchIn(text) -> GlError.GL_INVALID_ENUM
                Regex("(?i)(?:gl(?:_error)?|opengl error)\\s*[:=]?\\s*0x0501\\b").containsMatchIn(text) -> GlError.GL_INVALID_VALUE
                Regex("(?i)(?:gl(?:_error)?|opengl error)\\s*[:=]?\\s*0x0502\\b").containsMatchIn(text) -> GlError.GL_INVALID_OPERATION
                Regex("(?i)(?:gl(?:_error)?|opengl error)\\s*[:=]?\\s*0x0505\\b").containsMatchIn(text) -> GlError.GL_OUT_OF_MEMORY
                Regex("(?i)(?:gl(?:_error)?|opengl error)\\s*[:=]?\\s*0x0506\\b").containsMatchIn(text) -> GlError.GL_INVALID_FRAMEBUFFER_OPERATION
                else -> null
            }
            val terms = buildSet {
                if (low.contains("reserved")) add(Term.RESERVED_WORD)
                if (low.contains("undeclared") || low.contains("undefined identifier")) add(Term.UNDECLARED_IDENTIFIER)
                if (low.contains("overload") || low.contains("no matching")) add(Term.NO_MATCHING_OVERLOAD)
                if (low.contains("type mismatch") || low.contains("cannot convert") || low.contains("cannot implicitly convert")) add(Term.TYPE_MISMATCH)
                if (low.contains("unexpected")) add(Term.UNEXPECTED_TOKEN)
                if (Regex("\\bexpect(?:ed|ing)\\b").containsMatchIn(low)) add(Term.EXPECTED_TOKEN)
                if (low.contains("redefinition") || low.contains("already defined")) add(Term.REDEFINITION)
                if (low.contains("read-only") || low.contains("readonly")) add(Term.READ_ONLY)
                if (low.contains("index") && low.contains("array")) add(Term.ARRAY_INDEX)
                if (low.contains("swizzle")) add(Term.SWIZZLE)
                if (low.contains("syntax error")) add(Term.SYNTAX_ERROR)
            }
            val compiler = terms.isNotEmpty() || location != null || code != null || low.contains("compil") || low.contains("syntax error") || low.contains("no precision")
            val pair = when {
                low.contains("precision") && (low.contains("no precision") || low.contains("missing") || low.contains("not specified")) ->
                    Category.SHADER_COMPILE_ERROR to if (low.contains("sampler")) Reason.SAMPLER_PRECISION_MISSING else Reason.FLOAT_PRECISION_MISSING
                low.contains("temporary") && (low.contains("register") || low.contains("limit")) -> Category.GPU_RESOURCE_LIMIT to Reason.TEMPORARY_REGISTER_LIMIT
                low.contains("instruction") && (low.contains("limit") || low.contains("too many")) -> Category.GPU_RESOURCE_LIMIT to Reason.INSTRUCTION_LIMIT
                (low.contains("sampler") || low.contains("texture image units")) && (low.contains("too many") || low.contains("limit") || low.contains("exceed")) -> Category.GPU_RESOURCE_LIMIT to Reason.SAMPLER_LIMIT
                low.contains("out of memory") || gl == GlError.GL_OUT_OF_MEMORY -> Category.GPU_RESOURCE_LIMIT to Reason.OUT_OF_MEMORY
                low.contains("resource") && (low.contains("limit") || low.contains("exceed")) -> Category.GPU_RESOURCE_LIMIT to Reason.RESOURCE_LIMIT
                low.contains("link") && (low.contains("program") || low.contains("shader")) -> Category.SHADER_LINK_ERROR to Reason.LINK_FAILED
                compiler -> Category.SHADER_COMPILE_ERROR to if (low.contains("extension") && (low.contains("unsupported") || low.contains("not supported"))) Reason.EXTENSION_UNSUPPORTED else if (low.contains("precision")) Reason.PRECISION_MISMATCH else if (location != null || code != null) Reason.SYNTAX_OR_TYPE_ERROR else Reason.COMPILE_FAILED
                low.contains("format") && (low.contains("texture") || low.contains("image") || low.contains("renderable") || low.contains("unsupported") || low.contains("unavailable")) -> Category.TEXTURE_FORMAT_ERROR to Reason.FORMAT_UNSUPPORTED
                low.contains("framebuffer") || low.contains("fbo") || gl == GlError.GL_INVALID_FRAMEBUFFER_OPERATION -> Category.FRAMEBUFFER_ERROR to Reason.FBO_INCOMPLETE
                low.contains("render target") || low.contains("allocat") && low.contains("texture") -> Category.RENDER_TARGET_ERROR to Reason.TARGET_ALLOCATION_FAILED
                gl == GlError.GL_INVALID_OPERATION -> Category.GL_INVALID_OPERATION to Reason.INVALID_OPERATION
                low.contains("dispatch") || low.contains("failed rendering") -> Category.SHADER_DISPATCH_ERROR to Reason.DISPATCH_FAILED
                low.contains("shader") && (low.contains("open") || low.contains("read")) -> Category.SHADER_LOAD_ERROR to Reason.FILE_OPEN_FAILED
                gl != null -> Category.LIBPLACEBO_ERROR to Reason.DRIVER_REPORTED_ERROR
                low.contains("shader") || low.contains("glsl") -> Category.UNKNOWN_RENDERER_SHADER_ERROR to Reason.RENDERER_REPORTED_ERROR
                origin.contains("libplacebo") && level <= 20 && listOf("failed", "error", "unable", "unsupported").any { low.contains(it) } -> Category.LIBPLACEBO_ERROR to Reason.DRIVER_REPORTED_ERROR
                else -> return null
            }
            val symbol = Symbol.entries.sortedByDescending { it.name.length }.firstOrNull { low.contains(it.name.lowercase()) }
            return RendererFailure(pair.first,pair.second,stage,location?.groupValues?.get(1)?.toIntOrNull(),code,gl,symbol,terms)
        }
    }
}

internal enum class RendererAttemptPhase { IDLE, OPTIONS, MODEL_INSTALL, SHADER_SUBMITTED, RENDER_CONFIRM, CONFIRMED, BASELINE }
internal data class RendererAttemptContext(val source: Long, val attempt: Long, val mode: QualityMode?,
    val phase: RendererAttemptPhase) {
    fun summary() = "source=" + source + ", attempt=" + attempt + ", mode=" + (mode?.name ?: "none") + ", phase=" + phase.name
}
