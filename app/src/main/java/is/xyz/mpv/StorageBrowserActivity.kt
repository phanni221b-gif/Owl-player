package `is`.xyz.mpv

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StorageBrowserActivity : AppCompatActivity(R.layout.activity_storage_browser) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var titleText: TextView
    private lateinit var scopeText: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var storageButton: Button
    private val adapter = StorageBrowserAdapter(::openEntry)

    private var roots: List<StorageRoot> = emptyList()
    private var currentRoot: StorageRoot? = null
    private var currentPath: String? = null
    private var displayedEntries: List<BrowserEntry> = emptyList()
    private var searchQuery: String? = null
    private var browseJob: Job? = null
    private var launchJob: Job? = null
    private var activeVideo: MediaVideo? = null
    private var activePlaylist: List<MediaVideo> = emptyList()
    private var restartBrowseOnStart = false

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (OwlSession.ending || isFinishing || isDestroyed) return@registerForActivityResult
        val fallback = activeVideo
        activeVideo = null
        if (result.data != null) {
            val index = result.data!!.getIntExtra("playlist_index", -1)
            val video = activePlaylist.getOrNull(index) ?: fallback
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OwlSession.attach(this) {
            browseJob?.cancel(); launchJob?.cancel(); activeVideo = null; activePlaylist = emptyList()
        }
        SystemBarInsets.apply(findViewById(R.id.storageBrowserRoot))

        recycler = findViewById(R.id.storageBrowserRecycler)
        emptyText = findViewById(R.id.storageBrowserEmpty)
        titleText = findViewById(R.id.storageBrowserTitle)
        scopeText = findViewById(R.id.storageBrowserSearchScope)
        swipe = findViewById(R.id.storageBrowserSwipe)
        storageButton = findViewById(R.id.storageBrowserStorageButton)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        OwlDiagnostics.attach(this, this, recycler)

        findViewById<ImageButton>(R.id.storageBrowserBack).setOnClickListener { navigateBack() }
        findViewById<ImageButton>(R.id.storageBrowserSearch).setOnClickListener { showSearchDialog() }
        storageButton.setOnClickListener { showStorageMenu() }
        findViewById<Button>(R.id.storageBrowserFolderModeButton).setOnClickListener { finish() }
        swipe.setOnRefreshListener { refreshCurrent() }
        onBackPressedDispatcher.addCallback(this) { navigateBack() }

        roots = StorageBrowser.roots(this)
        val requestedRoot = intent.getStringExtra(EXTRA_ROOT_PATH)
        if (!requestedRoot.isNullOrBlank()) {
            currentRoot = StorageBrowser.rootForPath(roots, requestedRoot)
            currentPath = currentRoot?.let { if (StorageBrowser.containsPath(it.path, requestedRoot)) requestedRoot else it.path }
        }
        updateStorageButton()

        val requestedQuery = intent.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (requestedQuery.isNotEmpty()) runSearch(requestedQuery) else loadCurrent()
    }

    override fun onStart() {
        super.onStart()
        if (restartBrowseOnStart) {
            restartBrowseOnStart = false
            refreshCurrent()
        }
    }

    override fun onResume() {
        super.onResume()
        val latest = StorageBrowser.roots(this)
        roots = latest
        val path = currentPath
        if (path != null) {
            val available = StorageBrowser.rootForPath(latest, path)
            if (available == null || !File(path).exists()) {
                currentRoot = latest.firstOrNull { it.primary } ?: latest.firstOrNull()
                currentPath = currentRoot?.path
                searchQuery = null
                loadCurrent()
            } else {
                currentRoot = available
            }
        }
        updateStorageButton()
    }

    override fun onStop() {
        if (browseJob?.isActive == true) restartBrowseOnStart = true
        browseJob?.cancel()
        super.onStop()
    }

    private fun refreshCurrent() {
        val query = searchQuery
        if (query.isNullOrBlank()) loadCurrent() else runSearch(query)
    }

    private fun loadCurrent() {
        if (OwlSession.ending || isFinishing || isDestroyed) return
        browseJob?.cancel()
        searchQuery = null
        scopeText.visibility = View.GONE
        emptyText.visibility = View.VISIBLE
        emptyText.text = "불러오는 중…"
        updateTitle()

        val root = currentRoot
        val path = currentPath
        if (root == null || path == null) {
            displayedEntries = StorageBrowser.rootEntries(roots)
            adapter.submitList(displayedEntries)
            emptyText.visibility = if (displayedEntries.isEmpty()) View.VISIBLE else View.GONE
            if (displayedEntries.isEmpty()) emptyText.text = "사용 가능한 저장소가 없습니다."
            swipe.isRefreshing = false
            return
        }

        browseJob = lifecycleScope.launch {
            try {
                val entries = StorageBrowser.listDirectory(root, path)
                if (!isFinishing && !isDestroyed && !OwlSession.ending) {
                    displayedEntries = entries
                    adapter.submitList(entries)
                    emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    if (entries.isEmpty()) emptyText.text = "표시할 폴더나 동영상이 없습니다."
                    updateTitle()
                }
            } finally {
                if (coroutineContext[Job] === browseJob) swipe.isRefreshing = false
            }
        }
    }

    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || OwlSession.ending || isFinishing || isDestroyed) return
        browseJob?.cancel()
        searchQuery = query
        emptyText.visibility = View.VISIBLE
        emptyText.text = "검색 중…"
        titleText.text = "검색: $query"
        val scope = currentPath?.let { path ->
            val root = currentRoot
            if (root != null && path == root.path) root.label else File(path).name.ifBlank { root?.label ?: path }
        } ?: "전체 저장소"
        scopeText.text = "검색 범위: $scope 이하"
        scopeText.visibility = View.VISIBLE

        browseJob = lifecycleScope.launch {
            try {
                val result = StorageBrowser.search(roots, currentPath, query)
                if (!isFinishing && !isDestroyed && !OwlSession.ending) {
                    displayedEntries = result.entries
                    adapter.submitList(result.entries)
                    emptyText.visibility = if (result.entries.isEmpty()) View.VISIBLE else View.GONE
                    if (result.entries.isEmpty()) emptyText.text = "검색 결과가 없습니다."
                    scopeText.text = buildString {
                        append("검색 범위: $scope 이하 · ${result.entries.size}개")
                        if (result.truncated) append(" · 최대 ${StorageBrowser.MAX_SEARCH_RESULTS}개 표시")
                    }
                }
            } finally {
                if (coroutineContext[Job] === browseJob) swipe.isRefreshing = false
            }
        }
    }

    private fun openEntry(entry: BrowserEntry) {
        when (entry.kind) {
            BrowserEntryKind.STORAGE, BrowserEntryKind.DIRECTORY -> {
                val root = StorageBrowser.rootForPath(roots, entry.path) ?: return
                currentRoot = root
                currentPath = entry.path
                savePreferredStorage(root.path)
                updateStorageButton()
                loadCurrent()
            }
            BrowserEntryKind.VIDEO -> entry.video?.let(::playVideo)
        }
    }

    private fun playVideo(video: MediaVideo) {
        if (OwlSession.ending || isFinishing || isDestroyed || launchJob?.isActive == true) return
        val videos = displayedEntries.mapNotNull { it.video }
        val snapshot = if (videos.any { it.uri == video.uri }) videos else listOf(video)
        browseJob?.cancel()
        OwlSession.stopBrowserWork()
        launchJob = lifecycleScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val index = snapshot.indexOfFirst { it.uri == video.uri }.coerceAtLeast(0)
                index to Utils.makeMemoryPlaylist(snapshot.map { it.path?.takeIf { path -> path.isNotBlank() } ?: it.uri })
            }
            val history = withContext(Dispatchers.IO) {
                if (ApvSettingsActivity.isResumeEnabled(this@StorageBrowserActivity))
                    PlaybackHistory.get(this@StorageBrowserActivity, video) else null
            }
            activeVideo = video
            activePlaylist = snapshot
            OwlLaunchState.playlistStartIndex = prepared.first
            OwlLaunchState.resumePositionMs = history?.positionMs ?: -1L
            playerLauncher.launch(Intent(this@StorageBrowserActivity, MPVActivity::class.java).apply {
                putExtra("filepath", prepared.second)
            })
        }
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "파일 또는 폴더 이름"
            setSingleLine(true)
            searchQuery?.let { setText(it); setSelection(text.length) }
        }
        AlertDialog.Builder(this)
            .setTitle("현재 위치 이하 검색")
            .setView(input)
            .setPositiveButton("검색") { _, _ -> runSearch(input.text.toString()) }
            .setNeutralButton("검색 종료") { _, _ -> loadCurrent() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showStorageMenu() {
        roots = StorageBrowser.roots(this)
        val labels = arrayOf("전체 저장소", *roots.map { it.label }.toTypedArray())
        val checked = currentRoot?.let { root -> roots.indexOfFirst { it.path == root.path }.takeIf { it >= 0 }?.plus(1) } ?: 0
        AlertDialog.Builder(this)
            .setTitle("저장소 선택")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (which == 0) {
                    currentRoot = null
                    currentPath = null
                    savePreferredStorage(null)
                } else {
                    currentRoot = roots.getOrNull(which - 1)
                    currentPath = currentRoot?.path
                    savePreferredStorage(currentRoot?.path)
                }
                updateStorageButton()
                loadCurrent()
                dialog.dismiss()
            }
            .show()
    }

    private fun navigateBack() {
        if (!searchQuery.isNullOrBlank()) {
            loadCurrent()
            return
        }
        val root = currentRoot
        val path = currentPath
        if (root == null || path == null) {
            finish()
            return
        }
        if (path == root.path) {
            currentRoot = null
            currentPath = null
            updateStorageButton()
            loadCurrent()
            return
        }
        val parent = File(path).parentFile?.absolutePath
        currentPath = if (parent != null && StorageBrowser.containsPath(root.path, parent)) parent else root.path
        loadCurrent()
    }

    private fun updateTitle() {
        if (!searchQuery.isNullOrBlank()) return
        val root = currentRoot
        val path = currentPath
        titleText.text = when {
            root == null || path == null -> "저장소"
            path == root.path -> root.label
            else -> File(path).name.ifBlank { root.label }
        }
    }

    private fun updateStorageButton() {
        storageButton.text = currentRoot?.label ?: "전체 저장소"
    }

    private fun savePreferredStorage(path: String?) {
        val edit = getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
        if (path.isNullOrBlank()) edit.remove(KEY_STORAGE_ROOT) else edit.putString(KEY_STORAGE_ROOT, path)
        edit.apply()
    }

    companion object {
        const val EXTRA_ROOT_PATH = "owl_storage_root_path"
        const val EXTRA_QUERY = "owl_storage_query"
        const val PREFS_UI = "owl_ui"
        const val KEY_STORAGE_ROOT = "storage_root"
    }
}
