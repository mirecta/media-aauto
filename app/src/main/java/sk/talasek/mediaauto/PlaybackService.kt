package sk.talasek.mediaauto

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import android.os.Bundle
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import sk.talasek.mediaauto.library.Library
import sk.talasek.mediaauto.sosac.StreamResolver
import sk.talasek.mediaauto.ui.PlayerActivity

/**
 * Prehravanie a strom poloziek pre Android Auto aj telefon.
 *
 * Video stopa je predvolene vypnuta (v aute sa obraz zobrazit neda); zapina ju len PlayerActivity v telefone.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    companion object {
        private const val TICK_MS = 5_000L
        private const val SYNC_EVERY = 6 // * TICK_MS = kazdych 30 s
        private const val CMD_VIDEO = "sk.talasek.mediaauto.VIDEO"
        private val VIDEO_COMMAND = SessionCommand(CMD_VIDEO, Bundle.EMPTY)

        /**
         * Prihlasi/odhlasi obrazovku, ktora zobrazuje obraz (telefon, displej auta). Video stopa je zapnuta,
         * kym ju potrebuje aspon jedna; tak si obrazovky navzajom obraz nevypnu.
         */
        fun claimVideo(controller: MediaController, id: String, on: Boolean) {
            controller.sendCustomCommand(VIDEO_COMMAND, Bundle().apply {
                putString("id", id)
                putBoolean("on", on)
            })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: App
    private lateinit var library: Library
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    /** Kto prave potrebuje obraz (ID obrazovky -> jej pripojenie). */
    private val videoClaims = mutableMapOf<String, MediaSession.ControllerInfo>()

    private fun applyVideo() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoClaims.isEmpty())
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        app = App.get(this)
        library = app.library

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app.downloads.playbackFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        player.addListener(WatchSync())

        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(activity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveProgress(player.currentMediaItem, player.currentPosition)
        session.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun isCar(controller: MediaSession.ControllerInfo) =
        session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)

    // ---- cas sledovania ----

    /** Ulozi poziciu lokalne (obnovenie prehravania) a pri Sosac videu aj na server. */
    private fun saveProgress(item: MediaItem?, positionMs: Long) {
        if (item == null || positionMs <= 0) return
        app.prefs.lastMediaId = item.mediaId
        app.prefs.lastPositionMs = positionMs
        val ex = item.mediaMetadata.extras ?: return
        val kind = ex.getString(Library.K_KIND) ?: return
        val id = ex.getString(Library.K_ID) ?: return
        val seconds = positionMs / 1000
        library.updateWatched(item.mediaId, seconds.toDouble(), ex.getDouble(Library.K_DURATION))
        scope.launch { app.api.setWatchTime(kind, id, seconds) }
    }

    private inner class WatchSync : Player.Listener, Runnable {
        private val handler = Handler(Looper.getMainLooper())
        private var item: MediaItem? = null
        private var position = 0L
        private var ticks = 0
        private var retries = 0

        override fun run() {
            item = player.currentMediaItem
            position = player.currentPosition
            if (++ticks % SYNC_EVERY == 0) saveProgress(item, position)
            handler.postDelayed(this, TICK_MS)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(this)
            if (isPlaying) {
                ticks = 0
                retries = 0
                handler.postDelayed(this, TICK_MS)
            } else if (player.playbackState != Player.STATE_ENDED) {
                saveProgress(player.currentMediaItem, player.currentPosition)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = item
            if (previous != null && previous.mediaId != mediaItem?.mediaId) {
                // Pri automatickom prechode bola predchadzajuca polozka dopozerana.
                val end = previous.mediaMetadata.durationMs
                saveProgress(previous, if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && end != null) end else position)
            }
            item = mediaItem
            position = 0
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                val current = player.currentMediaItem
                saveProgress(current, current?.mediaMetadata?.durationMs ?: player.duration)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Pri chybe siete alebo neplatnom tokene skusime este raz: adresa sa pri otvoreni zisti znova.
            val sosac = player.currentMediaItem?.localConfiguration?.uri?.scheme == StreamResolver.SCHEME
            if (sosac && retries++ < 2) player.prepare()
        }
    }

    // ---- strom a rad ----

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(VIDEO_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != CMD_VIDEO) return super.onCustomCommand(session, controller, customCommand, args)
            val id = args.getString("id") ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            if (args.getBoolean("on")) videoClaims[id] = controller else videoClaims.remove(id)
            applyVideo()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            // Obrazovka sa odpojila bez odhlasenia (pad, zatvorenie) – jej narok na obraz zrusime.
            if (videoClaims.values.removeAll { it == controller }) applyVideo()
        }

        private fun <T> page(list: List<T>, page: Int, pageSize: Int): List<T> {
            if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return list
            val from = page.toLong() * pageSize
            if (from >= list.size) return emptyList()
            return list.subList(from.toInt(), minOf(list.size, from.toInt() + pageSize))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = android.os.Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            }
            return Futures.immediateFuture(
                LibraryResult.ofItem(library.root(), LibraryParams.Builder().setExtras(extras).build())
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val list = try {
                library.children(parentId)
            } catch (e: Exception) {
                listOf(library.errorItem(e))
            }
            LibraryResult.ofItemList(page(list, page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val item = try {
                library.item(mediaId)
            } catch (e: Exception) {
                null
            }
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val results = try {
                library.search(query)
            } catch (e: Exception) {
                emptyList()
            }
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val list = try {
                library.search(query)
            } catch (e: Exception) {
                listOf(library.errorItem(e))
            }
            LibraryResult.ofItemList(page(list, page, pageSize), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            library.resolveForQueue(mediaItems, isCar(controller)).toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            library.queue(mediaItems, startIndex, startPositionMs, isCar(controller))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val id = app.prefs.lastMediaId
            if (id.isEmpty()) throw UnsupportedOperationException("Nič na obnovenie")
            library.queue(listOf(MediaItem.Builder().setMediaId(id).build()), 0, app.prefs.lastPositionMs, isCar(controller))
        }
    }
}
