package com.perflyst.twire.tasks

import com.perflyst.twire.TwireApplication.Companion.helix
import com.perflyst.twire.model.Emote
import com.perflyst.twire.model.Emote.Companion.Twitch
import com.perflyst.twire.utils.Execute
import timber.log.Timber

/**
 * Created by idealMJ on 29/07/16.
 */
class GetTwitchEmotesTask(
    private val emoteSets: MutableList<String>,
    private val delegate: Delegate
) : Runnable {
    private val twitchEmotes: MutableList<Emote> = ArrayList()
    private val subscriberEmotes: MutableList<Emote> = ArrayList()

    override fun run() {
        // Network call on a worker thread: a flaky connection (or an empty
        // emote-sets list) used to throw straight out of run() and take the app
        // down. Deliver empty lists instead — chat just starts without Twitch
        // emotes, same as when the request times out upstream.
        try {
            val emotes = emoteSets.chunked(25).flatMap { set ->
                helix.getEmoteSets(null, set).execute().emotes ?: emptyList()
            }

            for (emoteData in emotes) {
                val emote = Twitch(emoteData.name, emoteData.id)
                if (emoteData.emoteSetId == "0") {
                    twitchEmotes.add(emote)
                } else {
                    emote.isSubscriberEmote = true
                    subscriberEmotes.add(emote)
                }
            }
            twitchEmotes.sort()

            Timber.tag("Chat").d("Found twitch emotes: %s", twitchEmotes.size)
        } catch (e: Exception) {
            Timber.e(e, "Twitch emote fetch failed — continuing without them")
        }

        Execute.ui { delegate.onEmotesLoaded(twitchEmotes, subscriberEmotes) }
    }

    fun interface Delegate {
        fun onEmotesLoaded(emotes: MutableList<Emote>?, subscriberEmotes: MutableList<Emote>?)
    }
}
