package com.esp32.smartnotif.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.esp32.smartnotif.R
import com.esp32.smartnotif.model.NotifLogItem

class LogAdapter(private val items: MutableList<NotifLogItem> = mutableListOf()) :
    RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAppBadge: TextView = view.findViewById(R.id.tvAppBadge)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notif_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvAppBadge.text = item.appCode
        holder.tvSender.text = item.sender
        holder.tvTime.text = item.timestamp
        holder.tvMessage.text = item.message
    }

    override fun getItemCount(): Int = items.size

    fun addItem(item: NotifLogItem) {
        items.add(0, item) // Tambah di paling atas
        if (items.size > 50) {
            items.removeAt(items.size - 1)
        }
        notifyItemInserted(0)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
