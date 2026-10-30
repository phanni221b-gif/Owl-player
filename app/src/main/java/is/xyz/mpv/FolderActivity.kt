package `is`.xyz.mpv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.concurrent.thread

class FolderActivity : AppCompatActivity(R.layout.activity_folder) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = VideoAdapter(::playVideo, ::showVideoActions)
    private lateinit var folderKey: String
    private var currentVideos: List<MediaVideo> = emptyList()
    private var displayedVideos: List<MediaVideo> = emptyList()
    private var sortMode = SORT_NAME
    private var activeVideo: MediaVideo? = null

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fallbackVideo = activeVideo
        activeVideo = null
        if (result.data != null) {
            val playedIndex = result.data!!.getIntExtra("playlist_index", -1)
            val video = displayedVideos.getOrNull(playedIndex) ?: fallbackVideo
            val position = result.data!!.getIntExtra("position", -1)
            val duration = result.data!!.getIntExtra("duration", -1)
            if (video != null && position >= 0) {
                PlaybackHistory.save(
                    this,
                    video,
                    position.toLong(),
                    if (duration > 0) duration.toLong() else video.durationMs
                )
            }
        }
        adapter.refreshProgress()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarInsets.apply(findViewById(R.id.folderRoot))

        folderKey = intent.getStringExtra(EXTRA_FOLDER_KEY).orEmpty()
        val folderName = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: "폴더"
        sortMode = getSharedPreferences(PREFS_UI, MODE_PRIVATE).getInt(KEY_VIDEO_SORT, SORT_NAME)

        findViewById<TextView>(R.id.folderScreenTitle).text = folderName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.videoSortButton).setOnClickListener { showSortMenu() }

        recycler = findViewById(R.id.videoRecycler)
        emptyText = findViewById(R.id.videoEmptyText)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        showFolder()
    }

    private fun showFolder() {
        MediaLibrary.cache.firstOrNull { it.key == folderKey }?.let {
            currentVideos = it.videos
            applySort()
            emptyText.visibility = if (it.videos.isEmpty()) View.VISIBLE else View.GONE
            return
        }

        emptyText.visibility = View.VISIBLE
        emptyText.text = "영상을 불러오는 중…"
        thread(name = "OwlFolderScan") {
            val folder = MediaLibrary.scan(this).firstOrNull { it.key == folderKey }
            runOnUiThread {
                currentVideos = folder?.videos ?: emptyList()
                applySort()
                emptyText.visibility = if (currentVideos.isEmpty()) View.VISIBLE else View.GONE
                if (currentVideos.isEmpty()) emptyText.text = "영상이 없습니다."
            }
        }
    }

    private fun applySort() {
        displayedVideos = when (sortMode) {
            SORT_RECENT -> currentVideos.sortedWith(
                compareByDescending<MediaVideo> { it.modifiedSeconds }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )
            SORT_DURATION -> currentVideos.sortedWith(
                compareByDescending<MediaVideo> { it.durationMs }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )
            SORT_SIZE -> currentVideos.sortedWith(
                compareByDescending<MediaVideo> { it.sizeBytes }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )
            else -> currentVideos.sortedBy { it.title.lowercase(Locale.getDefault()) }
        }
        adapter.submit(displayedVideos)
    }

    private fun showSortMenu() {
        val items = arrayOf("이름순", "최근 영상순", "재생시간 긴 순", "용량 큰 순")
        val checked = when (sortMode) {
            SORT_RECENT -> 1
            SORT_DURATION -> 2
            SORT_SIZE -> 3
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("영상 정렬")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                sortMode = when (which) {
                    1 -> SORT_RECENT
                    2 -> SORT_DURATION
                    3 -> SORT_SIZE
                    else -> SORT_NAME
                }
                getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putInt(KEY_VIDEO_SORT, sortMode).apply()
                applySort()
                dialog.dismiss()
            }
            .show()
    }

    private fun playVideo(video: MediaVideo) {
        playVideo(video, resume = true)
    }

    private fun playVideo(video: MediaVideo, resume: Boolean) {
        activeVideo = video
        val startIndex = displayedVideos.indexOfFirst { it.uri == video.uri }.coerceAtLeast(0)
        val history = if (resume && ApvSettingsActivity.isResumeEnabled(this)) {
            PlaybackHistory.get(this, video)
        } else null

        // Use the exact order currently shown on screen. If the user selected name/recent/
        // duration/size sorting, Previous and Next follow that visible top-to-bottom order.
        val playlistEntries = displayedVideos.map { media ->
            media.path?.takeIf { it.isNotBlank() } ?: media.uri
        }
        val memoryPlaylist = Utils.makeMemoryPlaylist(playlistEntries)

        OwlLaunchState.playlistStartIndex = startIndex
        OwlLaunchState.resumePositionMs = history?.positionMs ?: -1L

        val playerIntent = Intent(this, MPVActivity::class.java).apply {
            putExtra("filepath", memoryPlaylist)
        }
        playerLauncher.launch(playerIntent)
    }

    private fun showVideoActions(video: MediaVideo) {
        val hasHistory = PlaybackHistory.get(this, video) != null
        val items = mutableListOf("처음부터 재생", "파일 정보")
        if (hasHistory) items.add(1, "이어보기 기록 지우기")

        AlertDialog.Builder(this)
            .setTitle(video.title)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "처음부터 재생" -> {
                        PlaybackHistory.clear(this, video)
                        adapter.refreshProgress()
                        playVideo(video, resume = false)
                    }
                    "이어보기 기록 지우기" -> {
                        PlaybackHistory.clear(this, video)
                        adapter.refreshProgress()
                    }
                    "파일 정보" -> showVideoInfo(video)
                }
            }
            .show()
    }

    private fun showVideoInfo(video: MediaVideo) {
        val resolution = if (video.width > 0 && video.height > 0) "${video.width} × ${video.height}" else "알 수 없음"
        val size = if (video.sizeBytes > 0L) String.format(Locale.US, "%.2f GB", video.sizeBytes / (1024.0 * 1024.0 * 1024.0)) else "알 수 없음"
        val source = video.path?.takeIf { it.isNotBlank() } ?: video.uri
        AlertDialog.Builder(this)
            .setTitle("파일 정보")
            .setMessage("해상도: $resolution\n용량: $size\n\n$source")
            .setPositiveButton("확인", null)
            .show()
    }

    companion object {
        const val EXTRA_FOLDER_KEY = "owl_folder_key"
        const val EXTRA_FOLDER_NAME = "owl_folder_name"
        // Retained only so the older ACTION_VIEW compatibility branch in MPVActivity compiles.
        // Owl's folder playback now uses a memory:// M3U playlist instead of this extra.
        const val EXTRA_OWL_PLAYLIST = "owl_sorted_playlist"

        private const val PREFS_UI = "owl_ui"
        private const val KEY_VIDEO_SORT = "video_sort"
        private const val SORT_NAME = 0
        private const val SORT_RECENT = 1
        private const val SORT_DURATION = 2
        private const val SORT_SIZE = 3
    }
}
