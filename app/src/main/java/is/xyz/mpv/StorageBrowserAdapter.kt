package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

internal class StorageBrowserAdapter(
    private val onClick: (BrowserEntry) -> Unit
) : ListAdapter<BrowserEntry, StorageBrowserAdapter.Holder>(object : DiffUtil.ItemCallback<BrowserEntry>() {
    override fun areItemsTheSame(oldItem: BrowserEntry, newItem: BrowserEntry) =
        oldItem.kind == newItem.kind && oldItem.path == newItem.path

    override fun areContentsTheSame(oldItem: BrowserEntry, newItem: BrowserEntry) = oldItem == newItem
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_storage_browser, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.browserEntryIcon)
        private val title: TextView = view.findViewById(R.id.browserEntryTitle)
        private val subtitle: TextView = view.findViewById(R.id.browserEntrySubtitle)

        fun bind(entry: BrowserEntry) {
            title.text = entry.name
            subtitle.text = entry.subtitle
            icon.setImageResource(
                if (entry.kind == BrowserEntryKind.VIDEO) R.drawable.ic_file_open_48dp
                else R.drawable.nnf_ic_folder_black_48dp
            )
            icon.contentDescription = entry.name
            itemView.setOnClickListener { onClick(entry) }
        }
    }
}
