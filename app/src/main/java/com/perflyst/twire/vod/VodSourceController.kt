package com.perflyst.twire.vod

import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.common.util.concurrent.ListenableFuture
import com.perflyst.twire.R
import com.perflyst.twire.service.Settings
import com.perflyst.twire.utils.Execute
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.function.Consumer

/** xQc's video source only. Chat always retains the original Twitch VOD/channel IDs. */
class VodSourceController(
    private val fragment: Fragment,
    private val twitchVodId: String,
    private val twitchStartedAtMs: Long,
    private val twitchDurationMs: Long,
    private val onReload: () -> Unit,
    private val onChatSyncChanged: () -> Unit
) {
    private var activeVod: KickVod? = null
    private var chosenVod: KickVod? = null
    private var request: ListenableFuture<*>? = null
    private var requestGeneration = 0
    private var listRequest: ListenableFuture<*>? = null
    private var listRequestGeneration = 0
    private var disposed = false
    private var dialog: AlertDialog? = null

    val isKick: Boolean get() = activeVod != null
    val kickVodId: String? get() = activeVod?.id
    val timeline: VodTimeline
        get() = VodTimeline(
            activeVod?.let { if (twitchStartedAtMs > 0) it.startedAtMs - twitchStartedAtMs else 0 } ?: 0,
            chatAdjustmentMs
        )
    val chatAdjustmentMs: Long
        get() = activeVod?.let { Settings.getKickChatOffset(twitchVodId, it.id) * 1000L } ?: 0
    val videoDurationMs: Long get() = activeVod?.durationMs ?: twitchDurationMs

    /** Null means the ordinary Twitch resolver should handle video playback. */
    fun resolve(onReady: (String?) -> Unit) {
        cancelListRequest()
        if (!Settings.useKickForVod(twitchVodId)) {
            cancelRequest()
            activeVod = null
            onReady(null)
            return
        }
        fetch({
            val savedId = Settings.getKickVodId(twitchVodId)
            val vod = chosenVod?.takeIf { it.id == savedId }
                ?: KickVodApi.listXqcVods().let { vods ->
                    if (savedId != null) vods.firstOrNull { it.id == savedId }
                    else KickVodApi.findMatch(vods, twitchStartedAtMs, twitchDurationMs)
                }
                ?: throw IOException("No unique matching public xQc Kick recording")
            vod to KickVodApi.playbackUrl(vod)
        }, { (vod, url) ->
            activeVod = vod
            chosenVod = vod
            Settings.setKickVodId(twitchVodId, vod.id)
            toast(fragment.getString(R.string.kick_vod_playing))
            onReady(url)
        }, {
            activeVod = null
            // Keep the preference so retry can resolve a fresh, short-lived playback URL.
            toast(fragment.getString(R.string.kick_vod_fallback))
            onReady(null)
        })
    }

    fun showMenu() {
        if (disposed || fragment.context == null) return
        val choices = mutableListOf(
            fragment.getString(R.string.vod_source_twitch),
            fragment.getString(R.string.vod_source_kick_auto),
            fragment.getString(R.string.vod_source_kick_choose)
        )
        if (isKick) choices += fragment.getString(R.string.vod_source_chat_sync)
        dialog?.dismiss()
        dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(if (isKick) R.string.vod_source_title_kick else R.string.vod_source_title_twitch)
            .setItems(choices.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        Settings.setUseKickForVod(twitchVodId, false)
                        onReload()
                    }
                    1 -> {
                        chosenVod = null
                        Settings.setKickVodId(twitchVodId, null)
                        Settings.setUseKickForVod(twitchVodId, true)
                        onReload()
                    }
                    2 -> chooseVod()
                    3 -> showChatSync()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseVod() {
        toast(fragment.getString(R.string.kick_vod_loading))
        fetch(KickVodApi::listXqcVods, { vods ->
            if (vods.isEmpty()) {
                toast(fragment.getString(R.string.kick_vod_empty))
                return@fetch
            }
            val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val titles = vods.map { vod ->
                val minutes = vod.durationMs / 60000
                "${dateFormat.format(Date(vod.startedAtMs))} · ${minutes / 60}h ${minutes % 60}m\n${vod.title}"
            }.toTypedArray()
            dialog?.dismiss()
            dialog = AlertDialog.Builder(fragment.requireContext())
                .setTitle(R.string.vod_source_kick_choose)
                .setItems(titles) { _, which ->
                    chosenVod = vods[which]
                    Settings.setKickVodId(twitchVodId, vods[which].id)
                    Settings.setUseKickForVod(twitchVodId, true)
                    onReload()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }, { toast(fragment.getString(R.string.kick_vod_load_failed)) }, forPicker = true)
    }

    private fun showChatSync() {
        val vod = activeVod ?: return
        val input = EditText(fragment.requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
            setText(Settings.getKickChatOffset(twitchVodId, vod.id).toString())
            selectAll()
        }
        dialog?.dismiss()
        val syncDialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.vod_source_chat_sync)
            .setMessage(R.string.kick_vod_sync_help)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.kick_vod_sync_reset) { _, _ ->
                Settings.setKickChatOffset(twitchVodId, vod.id, 0)
                onChatSyncChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog = syncDialog
        syncDialog.setOnShowListener {
            syncDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = input.text.toString().trim().toIntOrNull()
                if (seconds == null || seconds !in -86400..86400) {
                    input.error = fragment.getString(R.string.kick_vod_sync_invalid)
                } else {
                    Settings.setKickChatOffset(twitchVodId, vod.id, seconds)
                    onChatSyncChanged()
                    syncDialog.dismiss()
                }
            }
        }
        syncDialog.show()
    }

    private fun <T> fetch(
        block: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: () -> Unit,
        forPicker: Boolean = false
    ) {
        if (forPicker) cancelListRequest() else cancelRequest()
        val generation = if (forPicker) listRequestGeneration else requestGeneration
        val future = Execute.background(Callable { runCatching(block) }, Consumer { result ->
            val currentGeneration = if (forPicker) listRequestGeneration else requestGeneration
            if (disposed || generation != currentGeneration || fragment.view == null) return@Consumer
            result.fold(onSuccess, { onFailure() })
        })
        if (forPicker) listRequest = future else request = future
    }

    private fun cancelRequest() {
        requestGeneration++
        request?.cancel(true)
        request = null
    }

    private fun cancelListRequest() {
        listRequestGeneration++
        listRequest?.cancel(true)
        listRequest = null
    }

    fun dispose() {
        disposed = true
        cancelRequest()
        cancelListRequest()
        dialog?.dismiss()
        dialog = null
    }

    private fun toast(message: String) {
        fragment.context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }
}
