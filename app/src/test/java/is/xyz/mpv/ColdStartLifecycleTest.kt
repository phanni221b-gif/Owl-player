package `is`.xyz.mpv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the actual AndroidX registry and Android API 34 post-start callbacks, not a mocked state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ColdStartLifecycleTest {
    class ProbeActivity : ComponentActivity() {
        internal val scan = LifecycleScanState()
        var insideOnStart: Lifecycle.State? = null
        var observerState: Lifecycle.State? = null
        var starts = 0
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            scan.request() // cold launch with permission already granted
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    observerState = lifecycle.currentState
                    if (scan.foreground() != null) starts++
                }
            })
        }
        override fun onStart() { super.onStart(); insideOnStart = lifecycle.currentState }
    }
    @Test fun activityOnStartIsTooEarlyButLifecycleObserverStartsPendingScan() {
        val controller = Robolectric.buildActivity(ProbeActivity::class.java).create().start().resume()
        val a = controller.get()
        assertEquals(Lifecycle.State.CREATED, a.insideOnStart)
        assertTrue(a.observerState!!.isAtLeast(Lifecycle.State.STARTED))
        assertEquals(1, a.starts)
        assertEquals(LifecycleScanState.Phase.LOADING, a.scan.phase)
        controller.pause().stop().destroy()
    }
}
