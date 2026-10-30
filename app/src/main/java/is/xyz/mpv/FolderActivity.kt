package `is`.xyz.mpv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class FolderActivity : AppCompatActivity(R.layout.activity_folder) {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = VideoAdapter(::playVideo)
    private lateinit var folderKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarInsets.apply(findViewById(R.id.folderRoot))

        folderKey = intent.getStringExtra(EXTRA_FOLDER_KEY).orEmpty()
        val folderName = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: "폴더"

        findViewById<TextView>(R.id.folderScreenTitle).text = folderName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        recycler = findViewById(R.id.videoRecycler)
        emptyText = findViewById(R.id.videoEmptyText)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        showFolder()
    }

    private fun showFolder() {
        MediaLibrary.cache.firstOrNull { it.key == folderKey }?.let {
            adapter.submit(it.videos)
            emptyText.visibility = if (it.videos.isEmpty()) View.VISIBLE else View.GONE
            return
        }

        emptyText.visibility = View.VISIBLE
        emptyText.text = "영상을 불러오는 중…"
        thread(name = "OwlFolderScan") {
            val folder = MediaLibrary.scan(this).firstOrNull { it.key == folderKey }
            runOnUiThread {
                val videos = folder?.videos ?: emptyList()
                adapter.submit(videos)
                emptyText.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
                if (videos.isEmpty()) emptyText.text = "영상이 없습니다."
            }
        }
    }

    private fun playVideo(video: MediaVideo) {
        val playerIntent = if (!video.path.isNullOrBlank()) {
            Intent(this, MPVActivity::class.java).apply {
                putExtra("filepath", video.path)
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(video.uri)).apply {
                setClass(this@FolderActivity, MPVActivity::class.java)
            }
        }
        startActivity(playerIntent)
    }

    companion object {
        const val EXTRA_FOLDER_KEY = "owl_folder_key"
        const val EXTRA_FOLDER_NAME = "owl_folder_name"
    }
}
