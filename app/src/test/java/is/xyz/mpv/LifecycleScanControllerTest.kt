package `is`.xyz.mpv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LifecycleScanControllerTest {
    class ProbeActivity : ComponentActivity() {
        internal lateinit var scans: LifecycleScanController<String>
        val forces = mutableListOf<Boolean>()
        val gates = mutableListOf<CompletableDeferred<List<String>>>()
        val emitters = mutableListOf<(List<String>) -> Unit>()
        val snapshots = mutableListOf<List<String>>()
        internal val states = mutableListOf<LifecycleScanState.Phase>()
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            scans = LifecycleScanController(this, lifecycleScope, "test", { true }, { force, emit ->
                forces.add(force); emitters.add(emit)
                val gate = CompletableDeferred<List<String>>(); gates.add(gate); gate.await()
            }, { snapshots.add(it) }, { states.add(it) })
            if (intent.getBooleanExtra("initial", true)) scans.request()
        }
    }
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
    @Test fun coldLaunchRefreshCoalescingAndCompletedForegroundReturn() {
        val c = Robolectric.buildActivity(ProbeActivity::class.java).create()
        val a = c.get(); assertTrue(a.forces.isEmpty())
        c.start().resume(); drain(); assertEquals(listOf(false),a.forces)
        repeat(5) { a.scans.request(true) }; assertEquals(1,a.forces.size)
        a.gates[0].complete(listOf("primary")); drain()
        assertEquals(listOf(false,true),a.forces)
        a.gates[1].complete(listOf("primary","usb")); drain()
        assertEquals(LifecycleScanState.Phase.CONTENT,a.states.last())
        c.pause().stop().restart().start().resume(); drain(); assertEquals(2,a.forces.size)
        c.pause().stop().destroy()
    }
    @Test fun stoppedWorkCannotPublishAndForegroundResumesExactlyOnce() {
        val c = Robolectric.buildActivity(ProbeActivity::class.java).create().start().resume(); drain()
        val a = c.get(); c.pause().stop(); drain()
        assertEquals(LifecycleScanState.Phase.PENDING,a.states.last())
        c.restart().start().resume(); drain(); assertEquals(2,a.forces.size)
        a.emitters[0](listOf("stale")); a.gates[0].complete(listOf("stale-final")); drain()
        assertTrue(a.snapshots.isEmpty())
        a.emitters[1](listOf("primary")); drain(); assertEquals(listOf("primary"),a.snapshots.last())
        a.gates[1].complete(listOf("primary","usb")); drain()
        assertEquals(listOf("primary","usb"),a.snapshots.last())
        c.pause().stop().destroy()
    }
    @Test fun resumedPermissionCallbackAndRefreshAfterFailure() {
        val intent = android.content.Intent().putExtra("initial",false)
        val c = Robolectric.buildActivity(ProbeActivity::class.java,intent).create().start().resume(); drain()
        val a = c.get(); assertTrue(a.forces.isEmpty())
        a.scans.request(); drain(); assertEquals(1,a.forces.size)
        a.gates[0].completeExceptionally(IllegalStateException("provider failed")); drain()
        assertEquals(LifecycleScanState.Phase.ERROR,a.states.last()); assertFalse(a.scans.running)
        a.scans.request(true); drain(); assertEquals(listOf(false,true),a.forces)
        a.gates[1].complete(emptyList()); drain(); assertEquals(LifecycleScanState.Phase.EMPTY,a.states.last())
        c.pause().stop().destroy()
    }
    @Test fun cancelledPlaybackPreparationCanResumeWithoutAnotherLifecycleTransition() {
        val c = Robolectric.buildActivity(ProbeActivity::class.java).create().start().resume(); drain()
        val a = c.get(); a.scans.pause("playback preparation"); drain()
        assertFalse(a.scans.running)
        a.scans.resumeIfForeground(); drain(); assertEquals(2,a.forces.size)
        a.gates[1].complete(listOf("content")); drain()
        c.pause().stop().destroy()
    }
    @Test fun emergencyBalancedDoesNotInheritFailedUltraScalerPreferences() {
        val prefs = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("quality-test",0)
        prefs.edit().putString("video_scale","ewa_lanczossharp").putString("video_downscale","mitchell")
            .putString("video_debanding","gpu").apply()
        val options = QualityPlaybackOptions.options(QualityMode.BALANCED,prefs,false)
        assertEquals("bicubic",options["scale"]); assertEquals("bilinear",options["dscale"])
        assertEquals("no",options["deband"])
    }
}
