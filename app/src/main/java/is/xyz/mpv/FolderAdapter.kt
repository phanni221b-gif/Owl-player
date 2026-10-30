package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private val onClick: (MediaFolder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.Holder>() {
    private var allItems: List<MediaFolder> = emptyList()
    private var visibleItems: List<MediaFolder> = emptyList()

    fun submit(items: List<MediaFolder>) {
        allItems = items
        visibleItems = items
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        visibleItems = if (query.isBlank()) allItems else allItems.filter {
            it.name.contains(query, ignoreCase = true)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = visibleItems.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(visibleItems[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.folderIcon)
        private val title: TextView = view.findViewById(R.id.folderTitle)
        private val count: TextView = view.findViewById(R.id.folderCount)

        fun bind(item: MediaFolder) {
            title.text = item.name
            count.text = "${item.videos.size} 비디오"
            itemView.setOnClickListener { onClick(item) }
            icon.contentDescription = item.name
        }
    }
}
