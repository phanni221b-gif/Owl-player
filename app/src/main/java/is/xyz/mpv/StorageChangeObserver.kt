package `is`.xyz.mpv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only mounted-volume transitions trigger a debounced refresh. Existing primary rows stay visible. */
internal class StorageChangeObserver(private val activity: androidx.appcompat.app.AppCompatActivity,
    private val scope: CoroutineScope, private val changed: () -> Unit) : DefaultLifecycleObserver {
    private var debounce: Job? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            debounce?.cancel()
            debounce = scope.launch { delay(600); if (!OwlSession.ending) changed() }
        }
    }
    init { activity.lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL); addDataScheme("file")
        }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }
    override fun onStop(owner: LifecycleOwner) {
        debounce?.cancel()
        if (registered) { activity.unregisterReceiver(receiver); registered = false }
    }
}
