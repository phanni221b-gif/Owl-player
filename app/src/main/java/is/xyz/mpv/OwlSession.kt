package `is`.xyz.mpv

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Main-thread shell coordination. Home/stop does not request session exit. */
internal object OwlSession {
    private val cleanup = SessionCleanup()
    private val activities = linkedSetOf<Activity>()
    val ending: Boolean get() = cleanup.closed
    val generation: Long get() = cleanup.generation
    @Volatile var playerReady = false

    fun prepareLauncher(activity: Activity, fresh: Boolean) {
        if (!fresh) return
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val liveTasks = manager.appTasks.map { it.taskInfo.taskId }.toSet()
        if (activities.any { !it.isFinishing && it.taskId in liveTasks }) return
        exit(activity.applicationContext)
        cleanup.restart()
    }

    fun attach(activity: AppCompatActivity, onExit: () -> Unit = {}) {
        if (cleanup.closed) cleanup.restart()
        activities.add(activity)
        cleanup.register(activity) { try { onExit() } finally { if (!activity.isFinishing) activity.finish() } }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!ending) try {
                    activity.startService(Intent(activity, OwlTaskService::class.java))
                } catch (error: IllegalStateException) { Log.w("OwlSession", "Task monitor start not allowed", error) }
            }
            override fun onDestroy(owner: LifecycleOwner) {
                cleanup.unregister(activity)
                activities.remove(activity)
                if (activities.isEmpty()) activity.stopService(Intent(activity, OwlTaskService::class.java))
            }
        })
    }

    fun stopBrowserWork() {
        MediaLibrary.cancelActive()
        VideoThumbnailLoader.cancelAll(clearCache = false)
    }

    fun exit(context: Context, expectedGeneration: Long? = null) {
        cleanup.close(before = {
            MediaLibrary.resetSession()
            VideoThumbnailLoader.cancelAll(clearCache = true)
            OwlDiagnostics.resetRuntime()
        }, after = {
            activities.clear()
            playerReady = false
            BackgroundPlaybackService.stopAndClear(context, true)
            OwlLaunchState.clear()
            context.stopService(Intent(context, OwlTaskService::class.java))
        }, expectedGeneration = expectedGeneration)
    }
}
