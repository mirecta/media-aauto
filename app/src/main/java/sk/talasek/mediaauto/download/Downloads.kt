package sk.talasek.mediaauto.download

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import org.json.JSONObject
import sk.talasek.mediaauto.Prefs
import sk.talasek.mediaauto.library.Library
import sk.talasek.mediaauto.sosac.SosacApi
import sk.talasek.mediaauto.sosac.StreamResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Stiahnute Sosac videa na offline prehravanie.
 *
 * Subory su v cache Media3 pod klucom `sosac:<kind>:<id>`; prehravac pouziva ten isty kluc, takze
 * stiahnute video sa prehra z telefonu bez ohladu na kvalitu v URI a aj bez internetu.
 */
@OptIn(UnstableApi::class)
class Downloads(private val context: Context, private val prefs: Prefs, streams: StreamResolver) {

    /** Udaje o stiahnutej polozke, aby sa dala zobrazit a prehrat aj offline. */
    data class Info(
        val mediaId: String, val kind: String, val id: String, val link: String,
        val title: String, val subtitle: String, val art: String?, val durationSec: Double,
        val mediaType: Int, val album: String?, val resumeSec: Double
    ) {
        fun toJson(): ByteArray = JSONObject()
            .put("mediaId", mediaId).put("kind", kind).put("id", id).put("link", link)
            .put("title", title).put("subtitle", subtitle).put("art", art).put("duration", durationSec)
            .put("mediaType", mediaType).put("album", album).put("resume", resumeSec)
            .toString().toByteArray()

        companion object {
            fun from(download: Download): Info? = try {
                val j = JSONObject(String(download.request.data))
                Info(
                    j.getString("mediaId"), j.getString("kind"), j.getString("id"), j.getString("link"),
                    j.optString("title"), j.optString("subtitle"), j.optString("art").ifEmpty { null },
                    j.optDouble("duration", 0.0), j.optInt("mediaType"), j.optString("album").ifEmpty { null }, j.optDouble("resume", 0.0)
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        fun cacheKey(kind: String, id: String) = "sosac:$kind:$id"
    }

    private val database = StandaloneDatabaseProvider(context)
    private val cache = SimpleCache(
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads"), NoOpCacheEvictor(), database
    )

    /** Siet: HTTP a pre sosac:// URI zistenie adresy zo streamuj.tv. Textovu odpoved nikdy necitame ako video. */
    val upstreamFactory: DataSource.Factory = ResolvingDataSource.Factory(
        DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory()
                .setUserAgent(SosacApi.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(SosacApi.TIMEOUT)
                .setReadTimeoutMs(SosacApi.TIMEOUT)
                .setContentTypePredicate { type -> !type.startsWith("text/") && !type.contains("json") }
        )
    ) { spec ->
        if (spec.uri.scheme == StreamResolver.SCHEME) spec.withUri(streams.resolveBlocking(spec.uri)) else spec
    }

    /** Pre prehravac: najprv stiahnute subory, inak siet. Pocas streamovania sa do cache nezapisuje. */
    val playbackFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setCacheWriteDataSinkFactory(null)

    val manager = DownloadManager(context, database, cache, upstreamFactory, Executors.newFixedThreadPool(2)).apply {
        maxParallelDownloads = 2
        requirements = requirementsFor(prefs.downloadWifiOnly)
    }

    /** Stav podla mediaId, aby zoznamy nemuseli pre kazdu polozku citat databazu. */
    private val states = ConcurrentHashMap<String, Int>()

    init {
        manager.downloadIndex.getDownloads().use { c ->
            while (c.moveToNext()) states[c.download.request.id] = c.download.state
        }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, download: Download, finalException: Exception?) {
                states[download.request.id] = download.state
            }

            override fun onDownloadRemoved(m: DownloadManager, download: Download) {
                states.remove(download.request.id)
            }
        })
    }

    private fun requirementsFor(wifiOnly: Boolean) =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    fun setWifiOnly(wifiOnly: Boolean) {
        DownloadService.sendSetRequirements(context, MediaDownloadService::class.java, requirementsFor(wifiOnly), false)
    }

    fun state(mediaId: String): Int? = states[mediaId]

    fun isCompleted(mediaId: String) = states[mediaId] == Download.STATE_COMPLETED

    val hasAny get() = states.isNotEmpty()

    /** Vsetky stiahnutia, najnovsie prve. */
    fun list(): List<Download> {
        val out = mutableListOf<Download>()
        manager.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) out.add(c.download) }
        return out.sortedByDescending { it.startTimeMs }
    }

    fun get(mediaId: String): Download? = manager.downloadIndex.getDownload(mediaId)

    /** Zacne stahovat Sosac polozku (mediaId play/<kind>/<id>, extras z Library). */
    fun start(item: MediaItem, quality: String = prefs.downloadQuality): Boolean {
        val ex = item.mediaMetadata.extras ?: return false
        val kind = ex.getString(Library.K_KIND) ?: return false
        val id = ex.getString(Library.K_ID) ?: return false
        val link = ex.getString(Library.K_LINK) ?: return false
        val meta = item.mediaMetadata
        val info = Info(
            item.mediaId, kind, id, link, meta.title?.toString() ?: "", meta.subtitle?.toString() ?: "",
            meta.artworkUri?.toString(), ex.getDouble(Library.K_DURATION), meta.mediaType ?: 0,
            meta.albumTitle?.toString(), ex.getDouble(Library.K_RESUME)
        )
        val request = DownloadRequest.Builder(item.mediaId, StreamResolver.uri(kind, id, link, quality))
            .setCustomCacheKey(cacheKey(kind, id))
            .setData(info.toJson())
            .build()
        DownloadService.sendAddDownload(context, MediaDownloadService::class.java, request, false)
        states[item.mediaId] = Download.STATE_QUEUED
        // Plagat stiahneme hned, aby bol k dispozicii aj offline.
        meta.artworkUri?.let { uri -> Thread { runCatching { context.contentResolver.openInputStream(uri)?.close() } }.start() }
        return true
    }

    fun remove(mediaId: String) {
        DownloadService.sendRemoveDownload(context, MediaDownloadService::class.java, mediaId, false)
        states.remove(mediaId)
    }

    fun artUri(info: Info): Uri? = info.art?.let(Uri::parse)
}
