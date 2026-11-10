package `is`.xyz.mpv

import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import java.io.File

internal object OwlRenameDialog {
    /** Shows a single-line editor; the base name is preselected so the extension stays intact. */
    fun show(context: Context, title: String, current: String, onConfirm: (String) -> Unit) {
        val input = EditText(context).apply {
            setSingleLine(true)
            setText(current)
            val dot = current.lastIndexOf('.')
            setSelection(0, if (dot > 0) dot else current.length)
        }
        val pad = Utils.convertDp(context, 20f)
        val container = FrameLayout(context).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("변경") { _, _ ->
                val name = input.text.toString()
                if (OwlFileSafety.cleanName(name) == null) {
                    android.widget.Toast.makeText(context, "사용할 수 없는 이름입니다.", android.widget.Toast.LENGTH_SHORT).show()
                } else onConfirm(name)
            }
            .setNegativeButton("취소", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    fun migrateVideoState(context: Context, video: MediaVideo, target: File) {
        val old = MediaRules.identity(video)
        PlaybackHistory.moveSource(context, old, target.absolutePath)
        SeenVideos.moveSource(context, old, target.absolutePath)
    }

    fun migrateFolderState(context: Context, folder: MediaFolder, target: File) {
        for (video in folder.videos) {
            val path = video.path?.takeIf { it.isNotBlank() } ?: continue
            migrateVideoState(context, video, File(target, File(path).name))
        }
    }
}
