package com.perflyst.twire.adapters

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.perflyst.twire.adapters.MentionAdapter.SuggestionViewHolder
import com.perflyst.twire.databinding.MentionSuggestionBinding

/**
 * Frosty-style autocomplete: mentions (@user) + emotes (:keyword) with images.
 */
data class SuggestionItem(
    val text: String,
    val imageUrl: String?,
    val isEmote: Boolean
)

class MentionAdapter(private val mDelegate: MentionAdapterDelegate) :
    RecyclerView.Adapter<SuggestionViewHolder?>() {
    private var mentionSuggestions: MutableList<SuggestionItem>

    init {
        mentionSuggestions = ArrayList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = MentionSuggestionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val item = mentionSuggestions[position]
        holder.binding.txtSuggestion.text = (if (item.isEmote) ":" else "@") + item.text
        val image = holder.binding.imageSuggestion
        Glide.with(image).clear(image)
        image.setImageDrawable(null)
        if (item.imageUrl == null) {
            image.visibility = View.GONE
        } else {
            image.visibility = View.VISIBLE
            Glide.with(image)
                .load(item.imageUrl)
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        isFirstResource: Boolean
                    ): Boolean {
                        image.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        image.visibility = View.VISIBLE
                        return false
                    }
                })
                .into(image)
        }
        holder.itemView.setOnClickListener {
            mDelegate.onSuggestionClick(item)
        }
    }

    override fun getItemCount(): Int {
        return mentionSuggestions.size
    }

    override fun onViewRecycled(holder: SuggestionViewHolder) {
        Glide.with(holder.binding.imageSuggestion).clear(holder.binding.imageSuggestion)
        super.onViewRecycled(holder)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setSuggestions(suggestions: MutableList<SuggestionItem>) {
        mentionSuggestions = suggestions
        notifyDataSetChanged()
    }

    fun interface MentionAdapterDelegate {
        fun onSuggestionClick(suggestion: SuggestionItem)
    }

    class SuggestionViewHolder(binding: MentionSuggestionBinding) :
        BindingViewHolder<MentionSuggestionBinding>(binding)
}
