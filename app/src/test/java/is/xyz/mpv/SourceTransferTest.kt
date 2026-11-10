package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test

class SourceTransferTest {
    @Test fun explicitTransfersWin() {
        assertEquals(true, SourceTransfer.hdr("pq", null, "bt.2020", "hevc"))
        assertEquals(true, SourceTransfer.hdr("unknown", "hlg", null, "hevc"))
        assertEquals(false, SourceTransfer.hdr("bt.1886", null, "bt.709", "hevc"))
        assertEquals(false, SourceTransfer.hdr("unknown", "bt.1886", null, "hevc"))
    }

    @Test fun untaggedSdOnlyCodecIsSdr() {
        // Fold8 diagnostic.5: 854x480 H.264 under mediacodec reported HDR=unknown and skipped both models.
        assertEquals(false, SourceTransfer.hdr("unknown", "unknown", "unknown", "h264"))
        assertEquals(false, SourceTransfer.hdr(null, null, null, "mpeg4"))
    }

    @Test fun untaggedHdrCapableOrBt2020StaysUnknown() {
        assertNull(SourceTransfer.hdr("unknown", null, "unknown", "hevc"))
        assertNull(SourceTransfer.hdr("unknown", null, null, "av1"))
        assertNull(SourceTransfer.hdr("unknown", null, "bt.2020", "h264"))
        assertNull(SourceTransfer.hdr("", "", "", ""))
    }

    @Test fun policyStillRejectsUnknownAndHdr() {
        val src = SourceVideo(854, 480, 30.0, SourceTransfer.hdr("unknown", null, null, "h264"), "h264")
        assertNull(QualityPolicy().rejection(QualityMode.AI_MAX, src))
        assertEquals("source_transfer_unavailable",
            QualityPolicy().rejection(QualityMode.NEURAL, src.copy(hdr = null)))
        assertEquals("model_validated_for_sdr_only",
            QualityPolicy().rejection(QualityMode.NEURAL, src.copy(hdr = true)))
    }
}
