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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = FolderAdapter(::openFolder)
    private var waitingForAllFiles = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadFolders() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ApvSettingsActivity.ensureDefaults(this)

        recycler = findViewById(R.id.folderRecycler)
        emptyText = findViewById(R.id.emptyText)
        val spanCount = if (resources.configuration.screenWidthDp >= 500) 4 else 3
        recycler.layoutManager = GridLayoutManager(this, spanCount)
        recycler.adapter = adapter

        findViewById<ImageButton>(R.id.searchButton).setOnClickListener { showSearch() }
        findViewById<ImageButton>(R.id.refreshButton).setOnClickListener { loadFolders() }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, ApvSettingsActivity::class.java))
        }

        ensureAccess()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForAllFiles && hasAllFilesAccess()) {
            waitingForAllFiles = false
            ensureAccess()
        }
    }

    private fun ensureAccess() {
        if (BuildConfig.FLAVOR == "allstorage" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
            waitingForAllFiles = true
            AlertDialog.Builder(this)
                .setTitle("저장소 접근")
                .setMessage("Apv가 내부 저장소와 외장 SSD의 영상 폴더를 빠르게 찾으려면 모든 파일 접근 권한이 필요합니다.")
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

    private fun loadFolders() {
        emptyText.text = "영상 폴더를 찾는 중…"
        emptyText.visibility = View.VISIBLE
        thread(name = "ApvMediaScan") {
            val folders = MediaLibrary.scan(this)
            runOnUiThread {
                adapter.submit(folders)
                emptyText.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
                if (folders.isEmpty()) emptyText.text = "영상이 있는 폴더를 찾지 못했습니다."
            }
        }
    }

    private fun openFolder(folder: MediaFolder) {
        startActivity(Intent(this, FolderActivity::class.java).apply {
            putExtra(FolderActivity.EXTRA_FOLDER_KEY, folder.key)
            putExtra(FolderActivity.EXTRA_FOLDER_NAME, folder.name)
        })
    }

    private fun showSearch() {
        val input = EditText(this).apply { hint = "폴더 이름 검색" }
        AlertDialog.Builder(this)
            .setTitle("검색")
            .setView(input)
            .setPositiveButton("적용") { _, _ -> adapter.filter(input.text.toString()) }
            .setNeutralButton("초기화") { _, _ -> adapter.filter("") }
            .setNegativeButton("취소", null)
            .show()
    }
}
