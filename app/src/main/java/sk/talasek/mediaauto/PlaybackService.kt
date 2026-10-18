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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
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
import sk.talasek.mediaauto.sosac.SosacApi
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: App
    private lateinit var library: Library
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        app = App.get(this)
        library = app.library

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(SosacApi.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(SosacApi.TIMEOUT)
            .setReadTimeoutMs(SosacApi.TIMEOUT)
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == StreamResolver.SCHEME) spec.withUri(app.streams.resolveBlocking(spec.uri)) else spec
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
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
            // Odkaz zo streamuj mohol expirovat; dalsi pokus ho ziska znova.
            player.currentMediaItem?.localConfiguration?.uri?.let {
                if (it.scheme == StreamResolver.SCHEME) app.streams.invalidate(it)
            }
        }
    }

    // ---- strom a rad ----

    private inner class Callback : MediaLibrarySession.Callback {

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
