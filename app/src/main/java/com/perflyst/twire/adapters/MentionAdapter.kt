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
        if (item.imageUrl == null) {
            holder.binding.imageSuggestion.visibility = View.GONE
        } else {
            holder.binding.imageSuggestion.visibility = View.VISIBLE
            Glide.with(holder.itemView.context).load(item.imageUrl).into(holder.binding.imageSuggestion)
        }
        holder.itemView.setOnClickListener {
            mDelegate.onSuggestionClick(item)
        }
    }

    override fun getItemCount(): Int {
        return mentionSuggestions.size
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
