package `is`.xyz.mpv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class FolderActivity : AppCompatActivity(R.layout.activity_folder) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = VideoAdapter(::playVideo, ::onVideoSelectionChanged)
    private lateinit var folderKey: String
    private var currentVideos: List<MediaVideo> = emptyList()
    private var displayedVideos: List<MediaVideo> = emptyList()
    private var sortMode = SORT_NAME
    private var activeVideo: MediaVideo? = null
    private var pendingMoveVideos: List<MediaVideo> = emptyList()

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

    private val moveDestinationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val selected = pendingMoveVideos
        pendingMoveVideos = emptyList()
        if (result.resultCode != RESULT_OK || selected.isEmpty()) return@registerForActivityResult
        val path = result.data?.getStringExtra("path") ?: return@registerForActivityResult
        val destination = File(path)
        if (!destination.isDirectory) {
            Toast.makeText(this, "이동할 폴더를 선택해줘.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        performVideoMove(selected, destination)
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

        findViewById<View>(R.id.videoSelectionClose).setOnClickListener { adapter.clearSelection() }
        findViewById<View>(R.id.videoSelectAll).setOnClickListener { adapter.selectAll() }
        findViewById<View>(R.id.videoMoveSelected).setOnClickListener { startMoveSelectedVideos() }
        findViewById<View>(R.id.videoDeleteSelected).setOnClickListener { confirmDeleteSelectedVideos() }

        recycler = findViewById(R.id.videoRecycler)
        emptyText = findViewById(R.id.videoEmptyText)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        onBackPressedDispatcher.addCallback(this) {
            if (adapter.isSelectionMode()) adapter.clearSelection() else finish()
        }

        showFolder()
    }

    private fun onVideoSelectionChanged(count: Int) {
        findViewById<View>(R.id.folderToolbar).visibility = if (count == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.videoSelectionBar).visibility = if (count == 0) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.videoSelectionCount).text = "${count}개 선택"
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
        rescanFolder()
    }

    private fun rescanFolder() {
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

    private fun confirmDeleteSelectedVideos() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("정말 삭제하겠습니까?")
            .setMessage("선택한 ${selected.size}개 파일을 휴지통으로 이동하지 않고 완전히 삭제합니다.\n\n삭제 후에는 복구할 수 없습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("완전 삭제") { _, _ -> performVideoDelete(selected) }
            .show()
    }

    private fun performVideoDelete(selected: List<MediaVideo>) {
        Toast.makeText(this, "완전 삭제 중…", Toast.LENGTH_SHORT).show()
        thread(name = "OwlPermanentVideoDelete") {
            val result = OwlFileOperations.deleteVideos(this, selected)
            selected.forEach { PlaybackHistory.clear(this, it) }
            runOnUiThread {
                adapter.clearSelection()
                showOperationResult("삭제", result)
                rescanFolder()
            }
        }
    }

    private fun startMoveSelectedVideos() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        pendingMoveVideos = selected
        val defaultPath = selected.firstOrNull()?.path?.let(::File)?.parentFile?.absolutePath
        val intent = Intent(this, FilePickerActivity::class.java).apply {
            putExtra("skip", FilePickerActivity.FILE_PICKER)
            putExtra("allow_dir", true)
            putExtra("title", "이동할 폴더 선택")
            if (!defaultPath.isNullOrBlank()) putExtra("default_path", defaultPath)
        }
        moveDestinationLauncher.launch(intent)
    }

    private fun performVideoMove(selected: List<MediaVideo>, destination: File) {
        Toast.makeText(this, "이동 중…", Toast.LENGTH_SHORT).show()
        thread(name = "OwlVideoMove") {
            val result = OwlFileOperations.moveVideos(this, selected, destination)
            selected.forEach { PlaybackHistory.clear(this, it) }
            runOnUiThread {
                adapter.clearSelection()
                showOperationResult("이동", result)
                rescanFolder()
            }
        }
    }

    private fun showOperationResult(action: String, result: OwlFileOperationResult) {
        val message = if (result.failureCount == 0) {
            "${result.successCount}개 항목 ${action} 완료"
        } else {
            "${result.successCount}개 ${action} 완료 · ${result.failureCount}개 실패"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_FOLDER_KEY = "owl_folder_key"
        const val EXTRA_FOLDER_NAME = "owl_folder_name"
        const val EXTRA_OWL_PLAYLIST = "owl_sorted_playlist"

        private const val PREFS_UI = "owl_ui"
        private const val KEY_VIDEO_SORT = "video_sort"
        private const val SORT_NAME = 0
        private const val SORT_RECENT = 1
        private const val SORT_DURATION = 2
        private const val SORT_SIZE = 3
    }
}
