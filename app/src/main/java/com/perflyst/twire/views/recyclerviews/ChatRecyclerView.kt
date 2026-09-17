package com.perflyst.twire.views.recyclerviews

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Created by Sebastian Rask on 30-03-2016.
 */
class ChatRecyclerView : RecyclerView {
    private var amountScrolled = 0

    private var chatPaused: TextView? = null
    private var lastScrolled = false

    constructor(context: Context) : super(context) {
        setScrolledListener()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        setScrolledListener()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        setScrolledListener()
    }

    val isScrolled: Boolean
        get() = amountScrolled > 1 && adapter!!.itemCount > 0

    /** True only when the user deliberately scrolled up (banner showing). */
    val isUserPaused: Boolean
        get() = lastScrolled

    fun setChatPaused(chatPaused: TextView) {
        this.chatPaused = chatPaused
        chatPaused.setOnClickListener { v: View? ->
            smoothScrollToPosition(
                this.adapter!!.itemCount - 1
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (!this.isScrolled && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) scrollToPosition(
            adapter!!.itemCount - 1
        )
    }

    private fun setScrolledListener() {
        val chatRecyclerView = this
        this.addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = layoutManager as LinearLayoutManager?
                amountScrolled =
                    layoutManager!!.getItemCount() - layoutManager.findLastCompletelyVisibleItemPosition() - 1

                if (chatPaused == null) return

                // Reaching the bottom always resumes — even from layout passes.
                // Only real user scrolls (drag/fling) may pause: animated emote
                // re-layouts fire onScrolled while IDLE and used to pause chat
                // from a mere touch or entirely by themselves.
                val scrolled: Boolean = if (amountScrolled <= 1) {
                    false
                } else {
                    lastScrolled || chatRecyclerView.scrollState != SCROLL_STATE_IDLE
                }
                if (scrolled != lastScrolled) {
                    chatPaused!!.animate().alpha((if (scrolled) 1 else 0).toFloat()).start()
                    lastScrolled = scrolled
                }
            }
        })
    }
}
