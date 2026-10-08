package com.nexastream.app.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.R
import com.nexastream.app.databinding.ItemTvFeaturedMiniCardBinding
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow

class FeaturedMiniCardAdapter(
    var items: List<Show> = emptyList(),
    var selectedIndex: Int = 0,
    var onItemSelected: ((Show, Int) -> Unit)? = null,
    var onItemClicked: ((Show) -> Unit)? = null
) : RecyclerView.Adapter<FeaturedMiniCardAdapter.MiniCardViewHolder>() {

    inner class MiniCardViewHolder(
        val binding: ItemTvFeaturedMiniCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(show: Show, position: Int) {
            val isSelected = (position == selectedIndex)
            binding.root.isSelected = isSelected

            val posterUrl = show.poster ?: show.banner
            Glide.with(binding.root.context)
                .load(posterUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .into(binding.ivMiniCardPoster)

            val qualityText = show.quality
            binding.tvMiniCardQuality.isVisible = !qualityText.isNullOrEmpty()
            binding.tvMiniCardQuality.text = qualityText ?: ""

            binding.ivMiniCardPlayIcon.isVisible = isSelected

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val oldIndex = selectedIndex
                    selectedIndex = position
                    if (oldIndex != position) {
                        notifyItemChanged(oldIndex)
                        notifyItemChanged(position)
                    }
                    onItemSelected?.invoke(show, position)
                }
            }

            binding.root.setOnClickListener {
                onItemClicked?.invoke(show)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MiniCardViewHolder {
        val binding = ItemTvFeaturedMiniCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MiniCardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MiniCardViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    fun updateSelection(newIndex: Int) {
        if (items.isEmpty()) return
        val validIndex = newIndex.coerceIn(0, items.lastIndex)
        if (selectedIndex != validIndex) {
            val oldIndex = selectedIndex
            selectedIndex = validIndex
            notifyItemChanged(oldIndex)
            notifyItemChanged(selectedIndex)
        }
    }
}
