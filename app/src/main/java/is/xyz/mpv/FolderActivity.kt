package `is`.xyz.mpv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
    private lateinit var scans: LifecycleScanController<MediaVideo>
    private var fileJob: Job? = null
    private var launchJob: Job? = null
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
        activePlaylist = emptyList()
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
            if (::scans.isInitialized) scans.close(); launchJob?.cancel(); fileJob?.cancel(); adapter.cancelPending()
            activeVideo = null; activePlaylist = emptyList()
        }
        SystemBarInsets.apply(findViewById(R.id.folderRoot))

        folderKey = intent.getStringExtra(EXTRA_FOLDER_KEY).orEmpty()
        val folderName = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: "폴더"
        sortMode = getSharedPreferences(PREFS_UI, MODE_PRIVATE).getInt(KEY_VIDEO_SORT, SORT_NAME)

        findViewById<TextView>(R.id.folderScreenTitle).text = folderName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.videoSearchButton).setOnClickListener { showSearch() }
        findViewById<ImageButton>(R.id.videoSortButton).setOnClickListener { showSortMenu() }

        findViewById<View>(R.id.videoSelectionClose).setOnClickListener { adapter.clearSelection() }
        findViewById<View>(R.id.videoSelectAll).setOnClickListener { adapter.selectAll() }
        findViewById<View>(R.id.videoMoveSelected).setOnClickListener { startMoveSelectedVideos() }
        findViewById<View>(R.id.videoDeleteSelected).setOnClickListener { confirmDeleteSelectedVideos() }
        findViewById<View>(R.id.videoRenameSelected).setOnClickListener { renameSelectedVideo() }

        recycler = findViewById(R.id.videoRecycler)
        emptyText = findViewById(R.id.videoEmptyText)
        swipeRefresh = findViewById(R.id.folderSwipeRefresh)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        OwlDiagnostics.attach(this, this, recycler)
        scans = LifecycleScanController(this, lifecycleScope, "folder",
            allowed = { !OwlSession.ending && !isFinishing && !isDestroyed },
            scan = { _, _ -> MediaLibrary.refreshFolder(applicationContext, folderKey)?.videos.orEmpty() },
            data = { videos ->
                currentVideos = videos; applySort()
                emptyText.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
                if (videos.isEmpty()) emptyText.text = "영상이 없습니다."
            }, stateChanged = { phase ->
                swipeRefresh.isRefreshing = phase == LifecycleScanState.Phase.LOADING
                if (currentVideos.isEmpty()) {
                    emptyText.text = when (phase) {
                        LifecycleScanState.Phase.LOADING -> "영상을 불러오는 중…"
                        LifecycleScanState.Phase.PENDING -> "검색을 시작할 준비 중…"
                        LifecycleScanState.Phase.ERROR, LifecycleScanState.Phase.CANCELLED -> "아래로 당겨 다시 검색해주세요."
                        else -> emptyText.text
                    }
                }
            })
        swipeRefresh.setOnRefreshListener { rescanFolder() }

        onBackPressedDispatcher.addCallback(this) {
            if (adapter.isSelectionMode()) adapter.clearSelection() else finish()
        }

        showFolder()
    }

    private fun onVideoSelectionChanged(count: Int) {
        findViewById<View>(R.id.folderToolbar).visibility = if (count == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.videoSelectionBar).visibility = if (count == 0) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.videoSelectionCount).text = "${count}개 선택"
        findViewById<View>(R.id.videoRenameSelected).visibility = if (count == 1) View.VISIBLE else View.GONE
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

    private fun rescanFolder() = scans.request(force = true)

    override fun onStop() {
        adapter.pauseThumbnails(recycler, true)
        launchJob?.cancel()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        adapter.pauseThumbnails(recycler, false)
    }

    private fun applySort() {
        displayedVideos = MediaRules.sortVideos(currentVideos, sortMode)
        adapter.submit(displayedVideos)
    }

    private fun showSearch() {
        val input = EditText(this).apply {
            hint = "파일 또는 폴더 이름"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("현재 폴더 이하 검색")
            .setView(input)
            .setPositiveButton("검색") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isEmpty()) return@setPositiveButton
                if (!folderKey.startsWith(File.separator)) {
                    Toast.makeText(this, "이 저장소에서는 현재 폴더의 직접 영상만 검색할 수 있습니다.", Toast.LENGTH_LONG).show()
                    val matched = currentVideos.filter { it.title.contains(query, ignoreCase = true) }
                    displayedVideos = MediaRules.sortVideos(matched, sortMode)
                    adapter.submit(displayedVideos)
                    emptyText.visibility = if (displayedVideos.isEmpty()) View.VISIBLE else View.GONE
                    if (displayedVideos.isEmpty()) emptyText.text = "검색 결과가 없습니다."
                    return@setPositiveButton
                }
                startActivity(Intent(this, StorageBrowserActivity::class.java).apply {
                    putExtra(StorageBrowserActivity.EXTRA_ROOT_PATH, folderKey)
                    putExtra(StorageBrowserActivity.EXTRA_QUERY, query)
                })
            }
            .setNegativeButton("취소", null)
            .show()
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
        scans.pause("playback launch")
        OwlSession.stopBrowserWork()
        launchJob = lifecycleScope.launch {
            var launched = false
            try {
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
            launched = true
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { Toast.makeText(this@FolderActivity, "재생을 시작하지 못했습니다.", Toast.LENGTH_SHORT).show() }
            finally { if (!launched) scans.resumeIfForeground() }
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
        if (OwlSession.ending || isFinishing || isDestroyed || fileJob?.isActive == true) return
        Toast.makeText(this, "완전 삭제 중…", Toast.LENGTH_SHORT).show()
        fileJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()[Job]!!
                OwlFileOperations.deleteVideos(this@FolderActivity, selected) { job.ensureActive() }
            }
            withContext(Dispatchers.IO) { goneVideos(selected).forEach { PlaybackHistory.clear(this@FolderActivity, it) } }
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
        if (OwlSession.ending || isFinishing || isDestroyed || fileJob?.isActive == true) return
        Toast.makeText(this, "이동 중…", Toast.LENGTH_SHORT).show()
        fileJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()[Job]!!
                OwlFileOperations.moveVideos(this@FolderActivity, selected, destination) { job.ensureActive() }
            }
            withContext(Dispatchers.IO) { goneVideos(selected).forEach { PlaybackHistory.clear(this@FolderActivity, it) } }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                adapter.clearSelection()
                showOperationResult("이동", result)
                rescanFolder()
            }
        }
    }

    private fun showOperationResult(action: String, result: OwlFileOperationResult) {
        Toast.makeText(this, result.message(action), Toast.LENGTH_LONG).show()
    }

    /** Failed deletions/moves keep their resume position; only files that are really gone lose it. */
    private fun goneVideos(selected: List<MediaVideo>) =
        selected.filter { video -> video.path?.takeIf { it.isNotBlank() }?.let { !File(it).exists() } ?: false }

    private fun renameSelectedVideo() {
        val video = adapter.selectedItems().singleOrNull() ?: return
        OwlRenameDialog.show(this, "파일 이름 변경", video.path?.let { File(it).name } ?: video.title) { name ->
            if (OwlSession.ending || isFinishing || isDestroyed || fileJob?.isActive == true) return@show
            fileJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    OwlFileOperations.renameVideo(this@FolderActivity, video, name)
                        .onSuccess { target -> OwlRenameDialog.migrateVideoState(this@FolderActivity, video, target) }
                }
                if (isDestroyed || isFinishing) return@launch
                adapter.clearSelection()
                Toast.makeText(this@FolderActivity, result.fold({ "이름을 변경했습니다." }, { it.message ?: "이름을 바꾸지 못했습니다." }), Toast.LENGTH_LONG).show()
                if (result.isSuccess) rescanFolder()
            }
        }
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
