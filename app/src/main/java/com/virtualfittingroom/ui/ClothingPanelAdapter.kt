package com.virtualfittingroom.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.virtualfittingroom.R
import com.virtualfittingroom.model.ClothingItem

class ClothingPanelAdapter(
    private var items: List<ClothingItem> = emptyList(),
    private val onItemSelected: (ClothingItem?) -> Unit
) : RecyclerView.Adapter<ClothingPanelAdapter.ViewHolder>() {

    private var selectedId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgClothing: ImageView = view.findViewById(R.id.imgClothing)
        val tvName: TextView = view.findViewById(R.id.tvClothingName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clothing, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.tvName.text = item.name
        item.thumbnailBitmap?.let {
            holder.imgClothing.setImageBitmap(it)
        }

        holder.itemView.isSelected = (item.id == selectedId)
        holder.itemView.setOnClickListener {
            if (selectedId == item.id) {
                // Deselect
                selectedId = null
                onItemSelected(null)
            } else {
                selectedId = item.id
                onItemSelected(item)
            }
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<ClothingItem>) {
        items = newItems
        selectedId = null
        notifyDataSetChanged()
    }

    fun getSelectedId() = selectedId
}
