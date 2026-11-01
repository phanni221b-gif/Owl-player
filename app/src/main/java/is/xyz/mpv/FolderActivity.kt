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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

class FolderActivity : AppCompatActivity(R.layout.activity_folder) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val adapter = VideoAdapter(this, lifecycleScope, ::playVideo, ::onVideoSelectionChanged)
    private lateinit var folderKey: String
    private var currentVideos: List<MediaVideo> = emptyList()
    private var displayedVideos: List<MediaVideo> = emptyList()
    private var sortMode = SORT_NAME
    private var scanJob: Job? = null
    private var fileJob: Job? = null
    private var launchJob: Job? = null
    private var scanInterrupted = false
    private var activeVideo: MediaVideo? = null
    private var activePlaylist: List<MediaVideo> = emptyList()
    private var pendingMoveVideos: List<MediaVideo> = emptyList()

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (OwlSession.ending || isFinishing || isDestroyed) return@registerForActivityResult
        val fallbackVideo = activeVideo
        activeVideo = null
        if (result.data != null) {
            val playedIndex = result.data!!.getIntExtra("playlist_index", -1)
            val video = activePlaylist.getOrNull(playedIndex) ?: fallbackVideo
            activePlaylist = emptyList()
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
        if (OwlSession.ending || isFinishing || isDestroyed) return@registerForActivityResult
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
        OwlSession.attach(this) {
            scanJob?.cancel(); launchJob?.cancel(); fileJob?.cancel(); adapter.cancelPending()
            scanInterrupted = false; activeVideo = null; activePlaylist = emptyList()
        }
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
        swipeRefresh = findViewById(R.id.folderSwipeRefresh)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        OwlDiagnostics.attach(this, this, recycler)
        swipeRefresh.setOnRefreshListener { rescanFolder(showLoadingText = false) }

        onBackPressedDispatcher.addCallback(this) {
            if (adapter.isSelectionMode()) adapter.clearSelection() else finish()
        }

        showFolder()
    }

    private fun onVideoSelectionChanged(count: Int) {
        findViewById<View>(R.id.folderToolbar).visibility = if (count == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.videoSelectionBar).visibility = if (count == 0) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.videoSelectionCount).text = "${count}개 선택"
        if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = count == 0
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

    private fun rescanFolder(showLoadingText: Boolean = true) {
        if (OwlSession.ending || isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            scanInterrupted = true
            return
        }
        if (showLoadingText) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = "영상을 불러오는 중…"
        }
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            try {
                val folder = MediaLibrary.refreshFolder(applicationContext, folderKey)
                currentVideos = folder?.videos ?: emptyList()
                applySort()
                emptyText.visibility = if (currentVideos.isEmpty()) View.VISIBLE else View.GONE
                if (currentVideos.isEmpty()) emptyText.text = "영상이 없습니다."
            } finally { if (coroutineContext[Job] === scanJob) swipeRefresh.isRefreshing = false }
        }
    }

    override fun onStop() {
        adapter.pauseThumbnails(recycler, true)
        scanInterrupted = scanInterrupted || scanJob?.isActive == true
        scanJob?.cancel()
        launchJob?.cancel()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        adapter.pauseThumbnails(recycler, false)
        if (scanInterrupted) { scanInterrupted = false; rescanFolder(false) }
    }

    private fun applySort() {
        displayedVideos = MediaRules.sortVideos(currentVideos, sortMode)
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
        if (OwlSession.ending || isFinishing || isDestroyed) return
        if (launchJob?.isActive == true) return
        val snapshot = displayedVideos.toList()
        scanJob?.cancel()
        OwlSession.stopBrowserWork()
        launchJob = lifecycleScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val startIndex = snapshot.indexOfFirst { it.uri == video.uri }.coerceAtLeast(0)
                val memoryPlaylist = Utils.makeMemoryPlaylist(snapshot.map { it.path?.takeIf { p -> p.isNotBlank() } ?: it.uri })
                startIndex to memoryPlaylist
            }
            val history = withContext(Dispatchers.IO) {
                if (resume && ApvSettingsActivity.isResumeEnabled(this@FolderActivity))
                    PlaybackHistory.get(this@FolderActivity, video) else null
            }
            activeVideo = video
            activePlaylist = snapshot
            OwlLaunchState.playlistStartIndex = prepared.first
            OwlLaunchState.resumePositionMs = history?.positionMs ?: -1L
            playerLauncher.launch(Intent(this@FolderActivity, MPVActivity::class.java).apply {
                putExtra("filepath", prepared.second)
            })
        }
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
        if (OwlSession.ending || isFinishing || isDestroyed || fileJob?.isActive == true) return
        fileJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()[Job]!!
                OwlFileOperations.deleteVideos(this@FolderActivity, selected) { job.ensureActive() }
            }
            selected.forEach { PlaybackHistory.clear(this@FolderActivity, it) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
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
        if (OwlSession.ending || isFinishing || isDestroyed || fileJob?.isActive == true) return
        fileJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()[Job]!!
                OwlFileOperations.moveVideos(this@FolderActivity, selected, destination) { job.ensureActive() }
            }
            selected.forEach { PlaybackHistory.clear(this@FolderActivity, it) }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
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
