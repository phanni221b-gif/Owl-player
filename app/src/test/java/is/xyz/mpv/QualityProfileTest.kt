package `is`.xyz.mpv

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class QualityProfileTest {
    private fun source(w: Int = 854, h: Int = 480, hdr: Boolean = false, fps: Double = 24.0) = SourceVideo(w, h, fps, hdr, "h264")
    @Test fun portraitAndLandscapeUseTheSameEnclosingResolutionBox() {
        val examples = listOf(Triple(854,480,ResolutionBucket.SD), Triple(1280,720,ResolutionBucket.HD),
            Triple(1920,1080,ResolutionBucket.FHD), Triple(2560,1440,ResolutionBucket.QHD),
            Triple(3840,2160,ResolutionBucket.UHD), Triple(7680,4320,ResolutionBucket.UHD),
            Triple(1920,800,ResolutionBucket.FHD), Triple(2560,1080,ResolutionBucket.QHD),
            Triple(1080,1080,ResolutionBucket.FHD), Triple(960,540,ResolutionBucket.HD))
        for ((w,h,bucket) in examples) {
            assertEquals(bucket, ResolutionBucket.classify(w,h)); assertEquals(bucket, ResolutionBucket.classify(h,w))
        }
        assertNull(ResolutionBucket.classify(0, 1080)); assertNull(ResolutionBucket.classify(720,-1))
        assertEquals(ResolutionBucket.UHD, ResolutionBucket.classify(Int.MAX_VALUE,Int.MAX_VALUE))
    }
    @Test fun overlappingBucketsSelectOnlyMaxAndThenNeuralAndThenBase() {
        val settings = QualityConfiguration(QualityMode.SHARP, setOf(ResolutionBucket.SD,ResolutionBucket.HD), setOf(ResolutionBucket.SD))
        assertEquals(listOf(QualityMode.AI_MAX,QualityMode.NEURAL,QualityMode.ULTRA,QualityMode.SHARP,QualityMode.BALANCED), settings.candidates(source()))
        assertEquals(QualityMode.NEURAL, settings.candidates(source(1280,720)).first())
        assertEquals(QualityMode.SHARP, settings.candidates(source(1920,1080)).first())
        assertEquals(QualityMode.SHARP, settings.candidates(source(0,0)).first())
    }
    @Test fun maxFailureTriesNeuralEvenWhenItsOwnCheckboxDoesNotIncludeTheBucket() {
        val settings = QualityConfiguration(QualityMode.BALANCED, aiMax=setOf(ResolutionBucket.SD))
        val chain = QualityFallback(settings.candidates(source()))
        assertEquals(QualityMode.AI_MAX, chain.current)
        assertEquals(QualityMode.NEURAL, chain.fail("compile_failed"))
        assertEquals(QualityMode.ULTRA, chain.fail("model_load_failed"))
        assertEquals(QualityMode.BALANCED, chain.fail("quality_option_rejected"))
        assertNull(chain.fail("base_failed")); assertEquals(4, chain.reasons.size)
    }
    @Test fun fallbackNeverLoopsWhenUltraIsAlreadyTheBase() {
        val candidates = QualityConfiguration(QualityMode.ULTRA,setOf(ResolutionBucket.SD),setOf(ResolutionBucket.SD)).candidates(source())
        assertEquals(candidates.size,candidates.distinct().size)
        val chain = QualityFallback(candidates)
        repeat(candidates.size) { chain.fail("failed") }; assertNull(chain.current)
    }
    @Test fun hdrAndFuturePerformanceGatesRejectOnlyAppropriateNeuralTiers() {
        val policy = QualityPolicy(maxAiFps=30.0)
        assertEquals("model_validated_for_sdr_only", policy.rejection(QualityMode.NEURAL,source(hdr=true)))
        assertNull(policy.rejection(QualityMode.ULTRA,source(hdr=true)))
        assertEquals("gpu_next_unavailable", policy.rejection(QualityMode.AI_MAX,source(),false))
        assertNull(policy.rejection(QualityMode.SHARP,source(),false))
        assertEquals("fps_policy",policy.rejection(QualityMode.AI_MAX,source(fps=60.0)))
        assertNull(policy.rejection(QualityMode.NEURAL,source(fps=60.0)))
        assertEquals("thermal_policy", QualityPolicy(thermalRestricted=true).rejection(QualityMode.AI_MAX,source()))
        assertEquals("battery_saver_policy", QualityPolicy(batterySaver=true).rejection(QualityMode.NEURAL,source()))
    }
    @Test fun ultraRestoresFastRestrictionsWithoutChangingPacingOrDecoderSelection() {
        val ultra = QualityOptions.forMode(QualityMode.ULTRA)
        for (name in listOf("correct-downscaling","linear-downscaling","sigmoid-upscaling")) assertEquals("yes",ultra[name])
        assertEquals("fruit",ultra["dither"]); assertEquals("auto",ultra["hdr-compute-peak"])
        assertEquals("ewa_lanczossharp",ultra["scale"]); assertEquals("mitchell",ultra["dscale"])
        assertFalse(ultra.containsKey("hwdec")); assertFalse(ultra.containsKey("video-sync")); assertFalse(ultra.containsKey("display-fps-override"))
        assertFalse(ultra.containsKey("interpolation"))
    }
    @Test fun shippedModelsHaveExpectedChecksumsAndDamagedAssetsAreRejected() {
        val assets = listOf(File("src/main/assets/neural"), File("app/src/main/assets/neural")).first { it.isDirectory }
        for (model in listOf(NeuralModel.neural,NeuralModel.aiMax)) {
            val bytes = File(assets,model.file).readBytes()
            assertTrue(model.verifies(bytes)); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            assertFalse(model.verifies(bytes))
        }
        assertTrue(File(assets,NeuralModel.aiMax.file).length() > File(assets,NeuralModel.neural.file).length())
    }
    @Test fun noUpscaleBypassesCnnAndZoomOrRotationCanActivateItWithoutChangingTheSourcePlan() {
        assertEquals(false, NeuralGeometry.upscaleRequired(1920,1080,1920,1080))
        assertEquals(false, NeuralGeometry.upscaleRequired(1280,720,1664,936)) // exactly 1.3x is not enough
        assertEquals(true, NeuralGeometry.upscaleRequired(1280,720,1920,1080))
        assertEquals(true, NeuralGeometry.upscaleRequired(720,1280,1080,1920))
        assertNull(NeuralGeometry.upscaleRequired(720,1280,0,0))
    }
    @Test fun renderConfirmationRequiresActualMatchingCnnReconstructionPass() {
        assertNull(RenderPassSummary.parse(null))
        val pending = RenderPassSummary.parse("n8=0,n16=0,passes=4,avg_ns=42")!!
        assertFalse(pending.neural8); assertFalse(pending.neural16)
        val loaded = RenderPassSummary.parse("n8=0,n16=1,passes=12,avg_ns=12500")!!
        assertTrue(loaded.neural16); assertFalse(loaded.neural8); assertEquals(12500L,loaded.averageNs)
    }
}
