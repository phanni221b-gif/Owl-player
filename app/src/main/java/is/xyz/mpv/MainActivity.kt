package `is`.xyz.mpv

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
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

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var browseControls: View
    private lateinit var storageButton: Button
    private val adapter = FolderAdapter(::openFolder, ::onFolderSelectionChanged)
    private lateinit var scans: LifecycleScanController<MediaFolder>
    private var fileJob: Job? = null
    private var rootsJob: Job? = null
    private var foregroundEntries = 0
    private var waitingForAllFiles = false
    private var currentFolders: List<MediaFolder> = emptyList()
    private var storageRoots: List<StorageRoot> = emptyList()
    private var selectedStoragePath: String? = null
    private var sortMode = SORT_NAME
    private var pendingMoveFolders: List<MediaFolder> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadFolders() }

    private val moveDestinationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (OwlSession.ending || isFinishing || isDestroyed) return@registerForActivityResult
        val selected = pendingMoveFolders
        pendingMoveFolders = emptyList()
        if (result.resultCode != RESULT_OK || selected.isEmpty()) return@registerForActivityResult
        val path = result.data?.getStringExtra("path") ?: return@registerForActivityResult
        val destination = File(path)
        if (!destination.isDirectory) {
            Toast.makeText(this, "이동할 폴더를 선택해줘.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        performFolderMove(selected, destination)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OwlSession.prepareLauncher(this, savedInstanceState == null && isTaskRoot && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        OwlSession.attach(this) { if (::scans.isInitialized) scans.close(); fileJob?.cancel(); rootsJob?.cancel() }

        SystemBarInsets.apply(findViewById(R.id.mainRoot))
        ApvSettingsActivity.ensureDefaults(this)

        val prefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        sortMode = prefs.getInt(KEY_SORT, SORT_NAME)
        selectedStoragePath = prefs.getString(StorageBrowserActivity.KEY_STORAGE_ROOT, null)
        storageRoots = listOf(StorageRoot("내부 저장소", Environment.getExternalStorageDirectory().absolutePath, true))

        recycler = findViewById(R.id.folderRecycler)
        emptyText = findViewById(R.id.emptyText)
        swipeRefresh = findViewById(R.id.mainSwipeRefresh)
        browseControls = findViewById(R.id.mainBrowseControls)
        storageButton = findViewById(R.id.mainStorageButton)
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter
        OwlDiagnostics.attach(this, this, recycler)
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGridSpan() }
        recycler.post { updateGridSpan() }
        swipeRefresh.setOnRefreshListener { loadFolders(force = true) }

        findViewById<ImageButton>(R.id.searchButton).setOnClickListener { showSearch() }
        findViewById<ImageButton>(R.id.sortButton).setOnClickListener { showSortMenu() }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, ApvSettingsActivity::class.java))
        }
        storageButton.setOnClickListener { showStorageMenu() }
        findViewById<Button>(R.id.mainBrowseButton).setOnClickListener { openStorageBrowser() }
        updateStorageButton()

        findViewById<View>(R.id.mainSelectionClose).setOnClickListener { adapter.clearSelection() }
        findViewById<View>(R.id.mainSelectAll).setOnClickListener { adapter.selectAllVisible() }
        findViewById<View>(R.id.mainMoveSelected).setOnClickListener { startMoveSelectedFolders() }
        findViewById<View>(R.id.mainDeleteSelected).setOnClickListener { confirmDeleteSelectedFolders() }

        onBackPressedDispatcher.addCallback(this) {
            if (adapter.isSelectionMode()) adapter.clearSelection() else finish()
        }

        scans = LifecycleScanController(this, lifecycleScope, "main",
            allowed = { !OwlSession.ending && !isFinishing && !isDestroyed },
            scan = { force, emit -> MediaLibrary.scan(applicationContext, force, emit) },
            data = ::displayFolders, stateChanged = ::renderScanState)
        StorageChangeObserver(this, lifecycleScope) { refreshStorageRoots(true); loadFolders(force = true) }
        ensureAccess()
    }

    override fun onResume() {
        super.onResume()
        foregroundEntries++
        selectedStoragePath = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
            .getString(StorageBrowserActivity.KEY_STORAGE_ROOT, null)
        val hadSavedSelection = selectedStoragePath != null
        refreshStorageRoots(hadSavedSelection)
        updateStorageButton()
        if (waitingForAllFiles && hasAllFilesAccess()) {
            waitingForAllFiles = false
            ensureAccess()
        }
        recycler.post { updateGridSpan() }
        if (scans.completed) displayFolders(MediaLibrary.cache)
        else if (currentFolders.isNotEmpty()) applySort()
    }

    private fun refreshStorageRoots(fallbackToPrimary: Boolean) {
        if (rootsJob?.isActive == true) return
        val app = applicationContext
        rootsJob = lifecycleScope.launch {
            val roots = try {
                kotlinx.coroutines.withTimeoutOrNull(8_000) { rootsWorker.query({}) { StorageBrowser.roots(app) } }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { null }
            if (OwlSession.ending || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@launch
            if (roots != null) {
                val changed = roots.map { it.path }.toSet() != storageRoots.map { it.path }.toSet()
                storageRoots = roots
                // A mount while backgrounded has no foreground broadcast; rescan only on an inventory change.
                if (changed && foregroundEntries > 1 && scans.completed) loadFolders(force = true)
            }
            validateSelectedStorage(fallbackToPrimary)
            updateStorageButton()
            if (currentFolders.isNotEmpty()) applySort()
        }
    }

    private fun validateSelectedStorage(fallbackToPrimary: Boolean) {
        val selected = selectedStoragePath ?: return
        if (storageRoots.any { it.path == selected }) return
        if (!fallbackToPrimary) {
            selectedStoragePath = null
            return
        }
        selectedStoragePath = storageRoots.firstOrNull { it.primary }?.path
        persistStorageSelection()
    }

    private fun persistStorageSelection() {
        val edit = getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit()
        val selected = selectedStoragePath
        if (selected.isNullOrBlank()) edit.remove(StorageBrowserActivity.KEY_STORAGE_ROOT)
        else edit.putString(StorageBrowserActivity.KEY_STORAGE_ROOT, selected)
        edit.apply()
    }

    private fun updateStorageButton() {
        if (!::storageButton.isInitialized) return
        storageButton.text = selectedStoragePath?.let { selected ->
            storageRoots.firstOrNull { it.path == selected }?.label
        } ?: "전체 저장소"
    }

    private fun onFolderSelectionChanged(count: Int) {
        findViewById<View>(R.id.mainToolbar).visibility = if (count == 0) View.VISIBLE else View.GONE
        browseControls.visibility = if (count == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mainSelectionBar).visibility = if (count == 0) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.mainSelectionCount).text = "${count}개 선택"
        if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = count == 0
    }

    private fun updateGridSpan() {
        if (!::recycler.isInitialized) return
        val widthDp = if (recycler.width > 0) {
            recycler.width / resources.displayMetrics.density
        } else {
            resources.configuration.screenWidthDp.toFloat()
        }
        val spans = when {
            widthDp >= 850f -> 5
            widthDp >= 520f -> 4
            else -> 3
        }
        val manager = recycler.layoutManager as? GridLayoutManager ?: return
        if (manager.spanCount != spans) manager.spanCount = spans
    }

    private fun ensureAccess() {
        if (BuildConfig.FLAVOR == "allstorage" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
            waitingForAllFiles = true
            AlertDialog.Builder(this)
                .setTitle("저장소 접근")
                .setMessage("Owl이 내부 저장소와 외장 SSD의 영상 폴더를 빠르게 찾으려면 모든 파일 접근 권한이 필요합니다.")
                .setPositiveButton("설정 열기") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("나중에") { _, _ -> requestMediaPermissionOrLoad() }
                .show()
            return
        }
        requestMediaPermissionOrLoad()
    }

    private fun requestMediaPermissionOrLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(permission))
        } else {
            loadFolders()
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun loadFolders(force: Boolean = false) = scans.request(force)

    private fun renderScanState(phase: LifecycleScanState.Phase) {
        swipeRefresh.isRefreshing = phase == LifecycleScanState.Phase.LOADING
        if (scopedFolders().isNotEmpty()) return
        val message = when (phase) {
            LifecycleScanState.Phase.PENDING -> "검색을 시작할 준비 중…"
            LifecycleScanState.Phase.LOADING -> "영상 폴더를 찾는 중…"
            LifecycleScanState.Phase.ERROR -> "검색하지 못했습니다. 아래로 당겨 다시 검색해주세요."
            LifecycleScanState.Phase.CANCELLED -> "검색이 중단되었습니다. 아래로 당겨 다시 검색해주세요."
            else -> return
        }
        emptyText.text = message
        emptyText.visibility = View.VISIBLE
    }

    private fun displayFolders(folders: List<MediaFolder>) {
        currentFolders = folders
        applySort()
    }

    private fun scopedFolders(): List<MediaFolder> =
        StorageBrowser.filterFolders(currentFolders, selectedStoragePath)

    private fun applySort() {
        val scoped = scopedFolders()
        val sorted = when (sortMode) {
            SORT_COUNT -> scoped.sortedWith(
                compareByDescending<MediaFolder> { it.videos.size }
                    .thenBy { it.name.lowercase(Locale.getDefault()) }
            )
            SORT_RECENT -> scoped.sortedWith(
                compareByDescending<MediaFolder> { it.newestModifiedSeconds }
                    .thenBy { it.name.lowercase(Locale.getDefault()) }
            )
            else -> scoped.sortedBy { it.name.lowercase(Locale.getDefault()) }
        }
        adapter.submit(sorted)
        emptyText.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        if (sorted.isEmpty()) {
            emptyText.text = if (selectedStoragePath == null) {
                "영상이 있는 폴더를 찾지 못했습니다."
            } else {
                "선택한 저장소에서 영상 폴더를 찾지 못했습니다."
            }
        }
    }

    private fun showSortMenu() {
        val items = arrayOf("이름순", "영상 많은 순", "최근 영상순", "다시 검색")
        val checked = when (sortMode) {
            SORT_COUNT -> 1
            SORT_RECENT -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("폴더 정렬")
            .setSingleChoiceItems(items, checked) { dialog, which ->
                if (which == 3) {
                    dialog.dismiss()
                    loadFolders(force = true)
                    return@setSingleChoiceItems
                }
                sortMode = when (which) {
                    1 -> SORT_COUNT
                    2 -> SORT_RECENT
                    else -> SORT_NAME
                }
                getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putInt(KEY_SORT, sortMode).apply()
                applySort()
                dialog.dismiss()
            }
            .show()
    }

    private fun showStorageMenu() {
        storageRoots = StorageBrowser.roots(this)
        val labels = arrayOf("전체 저장소", *storageRoots.map { it.label }.toTypedArray())
        val checked = selectedStoragePath?.let { selected ->
            storageRoots.indexOfFirst { it.path == selected }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
        AlertDialog.Builder(this)
            .setTitle("저장소 선택")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectedStoragePath = if (which == 0) null else storageRoots.getOrNull(which - 1)?.path
                persistStorageSelection()
                updateStorageButton()
                adapter.clearSelection()
                applySort()
                dialog.dismiss()
            }
            .show()
    }

    private fun openStorageBrowser(query: String? = null) {
        startActivity(Intent(this, StorageBrowserActivity::class.java).apply {
            selectedStoragePath?.let { putExtra(StorageBrowserActivity.EXTRA_ROOT_PATH, it) }
            if (!query.isNullOrBlank()) putExtra(StorageBrowserActivity.EXTRA_QUERY, query)
        })
    }

    private fun openFolder(folder: MediaFolder) {
        startActivity(Intent(this, FolderActivity::class.java).apply {
            putExtra(FolderActivity.EXTRA_FOLDER_KEY, folder.key)
            putExtra(FolderActivity.EXTRA_FOLDER_NAME, folder.name)
        })
    }

    private fun showSearch() {
        val input = EditText(this).apply {
            hint = "파일 또는 폴더 이름"
            setSingleLine(true)
        }
        val scope = selectedStoragePath?.let { selected ->
            storageRoots.firstOrNull { it.path == selected }?.label
        } ?: "전체 저장소"
        AlertDialog.Builder(this)
            .setTitle("$scope 이하 검색")
            .setView(input)
            .setPositiveButton("검색") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) openStorageBrowser(query)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDeleteSelectedFolders() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("정말 삭제하겠습니까?")
            .setMessage("선택한 ${selected.size}개 폴더와 그 안의 모든 파일을 휴지통으로 이동하지 않고 완전히 삭제합니다.\n\n삭제 후에는 복구할 수 없습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("완전 삭제") { _, _ -> performFolderDelete(selected) }
            .show()
    }

    private fun performFolderDelete(selected: List<MediaFolder>) {
        Toast.makeText(this, "완전 삭제 중…", Toast.LENGTH_SHORT).show()
        if (OwlSession.ending || isFinishing || isDestroyed || fileJob?.isActive == true) return
        fileJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()[Job]!!
                OwlFileOperations.deleteFolders(this@MainActivity, selected) { job.ensureActive() }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                adapter.clearSelection()
                showOperationResult("삭제", result)
                loadFolders(force = true)
            }
        }
    }

    private fun startMoveSelectedFolders() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        pendingMoveFolders = selected
        val defaultPath = selected.firstOrNull()?.videos?.firstOrNull()?.path
            ?.let(::File)?.parentFile?.parentFile?.absolutePath
        val intent = Intent(this, FilePickerActivity::class.java).apply {
            putExtra("skip", FilePickerActivity.FILE_PICKER)
            putExtra("allow_dir", true)
            putExtra("title", "이동할 폴더 선택")
            if (!defaultPath.isNullOrBlank()) putExtra("default_path", defaultPath)
        }
        moveDestinationLauncher.launch(intent)
    }

    private fun performFolderMove(selected: List<MediaFolder>, destination: File) {
        Toast.makeText(this, "이동 중…", Toast.LENGTH_SHORT).show()
        if (OwlSession.ending || isFinishing || isDestroyed || fileJob?.isActive == true) return
        fileJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()[Job]!!
                OwlFileOperations.moveFolders(this@MainActivity, selected, destination) { job.ensureActive() }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                adapter.clearSelection()
                showOperationResult("이동", result)
                loadFolders(force = true)
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
        // Shared across Activity recreation: ignored OS cancellation cannot grow worker count.
        private val rootsWorker = ProviderQueryRunner(2, "OwlMainStorageRoots")
        private const val PREFS_UI = "owl_ui"
        private const val KEY_SORT = "folder_sort"
        private const val SORT_NAME = 0
        private const val SORT_COUNT = 1
        private const val SORT_RECENT = 2
    }
}
