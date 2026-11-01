package `is`.xyz.mpv

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** No foreground notification. Android may discard this monitor while the app is idle.
 * In that case browser stop already canceled its work, and a new launcher task resets memory. */
class OwlTaskService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTaskRemoved(rootIntent: Intent?) {
        OwlSession.exit(applicationContext)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
