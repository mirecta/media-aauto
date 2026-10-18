package sk.talasek.mediaauto.library

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sk.talasek.mediaauto.Prefs
import sk.talasek.mediaauto.R
import sk.talasek.mediaauto.sosac.SosacApi
import sk.talasek.mediaauto.sosac.SosacItem
import sk.talasek.mediaauto.sosac.StreamResolver
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Strom polozek pre Android Auto aj telefon.
 *
 * ID polozek:
 *   root                                      4 karty: Hudba, Videá, Filmy, Seriály
 *   music, music/artists, music/albums, music/songs, artist/<id>, album/<id>, song/<id>
 *   videos, video/<id>
 *   movies, serials                           menu zoznamov zo Sosac
 *   list/<kind>/<zoznam>/<strana>[/<param>/<hodnota>]
 *   pick/<kind>/<zoznam>/<param>              vyber hodnoty (pismeno, zaner, kvalita)
 *   serial/<id>, season/<serial>/<seria>      serie a epizody
 *   play/movies/<id>, play/episodes/<id>      prehratelne video zo Sosac
 *   info/<...>                                informacna polozka bez obsahu
 */
class Library(
    context: Context,
    private val api: SosacApi,
    private val prefs: Prefs,
    private val local: LocalMedia
) {
    companion object {
        const val ROOT = "root"
        const val MUSIC = "music"
        const val VIDEOS = "videos"
        const val MOVIES = "movies"
        const val SERIALS = "serials"

        const val K_KIND = "sosac.kind"
        const val K_ID = "sosac.id"
        const val K_LINK = "sosac.link"
        const val K_RESUME = "sosac.resume"
        const val K_DURATION = "sosac.duration"
        const val K_PARENT = "parent"

        private const val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        private const val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

        private val LETTERS = listOf("0-9") + ('a'..'z').map { it.toString() }
        private val GENRES = listOf(
            "action" to "Akčné", "adventure" to "Dobrodružné", "animation" to "Animované", "biography" to "Životopisné",
            "cartoons" to "Kreslené", "comedy" to "Komédie", "crime" to "Kriminálne", "disaster" to "Katastrofické",
            "documentary" to "Dokumentárne", "drama" to "Drámy", "erotic" to "Erotické", "experimental" to "Experimentálne",
            "fairytales" to "Rozprávky", "family" to "Rodinné", "fantasy" to "Fantasy", "history" to "Historické",
            "horror" to "Horory", "imax" to "IMAX", "krimi" to "Krimi", "music" to "Hudobné", "mystery" to "Mysteriózne",
            "psychological" to "Psychologické", "publicistic" to "Publicistické", "realitytv" to "Reality TV",
            "romance" to "Romantické", "sci-fi" to "Sci-Fi", "sport" to "Športové", "talkshow" to "Talkshow",
            "thriller" to "Thrillery", "war" to "Vojnové", "western" to "Westerny"
        )
        private val QUALITY_FILTER = listOf("uhd" to "UHD", "fhd" to "Full HD", "hd" to "HD", "sd" to "SD")
    }

    private val ctx = context.applicationContext

    /** Naposledy vytvorene polozky (podla ID) a ich rodic – na zostavenie radu po kliknuti. */
    private val items = ConcurrentHashMap<String, MediaItem>()
    private val parents = ConcurrentHashMap<String, String>()
    private val serials = LruCache<String, JSONObject>(20)
    private val searches = LruCache<String, List<MediaItem>>(10)
    /** Lokalne zmeneny cas sledovania (sekundy), kym sa zoznamy znova nenacitaju zo servera. */
    private val resumeOverride = ConcurrentHashMap<String, Double>()
    private val episodeJson = ConcurrentHashMap<String, JSONObject>()

    // ---- stavba poloziek ----

    private fun extras(block: Bundle.() -> Unit) = Bundle().apply(block)

    private fun icon(resId: Int) = ArtworkProvider.resource(ctx, resId)

    private fun folder(
        id: String, title: String, subtitle: String? = null, art: Uri? = null, group: String? = null,
        browsableStyle: Int = LIST, playableStyle: Int = LIST, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(art)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setExtras(extras {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsableStyle)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playableStyle)
                    if (group != null) putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
                })
                .build()
        )
        .build()

    private fun infoItem(id: String, title: String, subtitle: String? = null) = folder("info/$id", title, subtitle)

    fun errorItem(e: Throwable) = infoItem("error", e.message ?: "Neznáma chyba", "Skúste to znova neskôr")

    private fun playable(
        id: String, title: String, subtitle: String?, art: Uri?, type: Int,
        uri: Uri? = null, durationMs: Long? = null, artist: String? = null, album: String? = null,
        group: String? = null, extra: Bundle? = null
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .setSubtitle(subtitle)
                .setArtist(artist ?: subtitle)
                .setAlbumTitle(album)
                .setArtworkUri(art)
                .setDurationMs(durationMs?.takeIf { it > 0 })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(type)
                .setExtras((extra ?: Bundle()).apply {
                    if (group != null) putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
                })
                .build()
        )
        .build()

    private fun songItem(s: LocalMedia.Song, group: String? = null) = playable(
        "song/${s.id}", s.title, s.artist, ArtworkProvider.album(ctx, s.albumId), MediaMetadata.MEDIA_TYPE_MUSIC,
        uri = s.uri, durationMs = s.durationMs, artist = s.artist, album = s.album, group = group
    )

    private fun videoItem(v: LocalMedia.Video, group: String? = null) = playable(
        "video/${v.id}", v.title, v.folder, ArtworkProvider.video(ctx, v.id), MediaMetadata.MEDIA_TYPE_VIDEO,
        uri = v.uri, durationMs = v.durationMs, group = group
    )

    private fun sosacExtras(kind: String, item: JSONObject, parent: String? = null) = extras {
        putString(K_KIND, kind)
        putString(K_ID, SosacItem.id(item))
        putString(K_LINK, SosacItem.link(item))
        putDouble(K_RESUME, SosacItem.resumeSeconds(item))
        putDouble(K_DURATION, SosacItem.duration(item))
        if (parent != null) putString(K_PARENT, parent)
        val p = SosacItem.progress(item)
        putInt(
            MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, when {
                SosacItem.isWatched(item) -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                p > 0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            }
        )
        if (p > 0 && p < 1) putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, p)
    }

    private fun art(url: String?) = ArtworkProvider.web(ctx, SosacItem.image(url))

    private fun durationMs(item: JSONObject) = (SosacItem.duration(item) * 1000).toLong()

    private fun movieItem(item: JSONObject, group: String? = null): MediaItem? {
        if (SosacItem.link(item).isEmpty()) return null
        return playable(
            "play/movies/${SosacItem.id(item)}", SosacItem.title(item), SosacItem.meta(item), art(item.optString("i")),
            MediaMetadata.MEDIA_TYPE_MOVIE, durationMs = durationMs(item), group = group,
            extra = sosacExtras("movies", item)
        )
    }

    /** Epizoda zo zoznamu epizod alebo hladania (n = nazov serialu, ne = nazov epizody). */
    private fun episodeItem(item: JSONObject, group: String? = null): MediaItem? {
        if (SosacItem.link(item).isEmpty()) return null
        val serial = SosacItem.title(item)
        val title = listOf(SosacItem.episodeCode(item), SosacItem.episodeName(item)).filter { it.isNotEmpty() }.joinToString(" ")
        return playable(
            "play/episodes/${SosacItem.id(item)}", title.ifEmpty { serial }, serial, art(item.optString("i")),
            MediaMetadata.MEDIA_TYPE_TV_SHOW, durationMs = durationMs(item), album = serial, group = group,
            extra = sosacExtras("episodes", item)
        )
    }

    /** Epizoda z detailu serialu (n = nazov epizody). */
    private fun seasonEpisodeItem(serialId: String, info: JSONObject, season: String, num: String, ep: JSONObject, prefix: String = ""): MediaItem? {
        if (SosacItem.link(ep).isEmpty()) return null
        val serial = SosacItem.title(info)
        val code = "${season}x${SosacItem.pad(num.toIntOrNull() ?: 0)}"
        val epName = SosacItem.title(ep)
        val id = "play/episodes/${SosacItem.id(ep)}"
        episodeJson[id] = ep
        return playable(
            id, if (prefix.isEmpty()) listOf("$num.", epName).filter { it.isNotEmpty() }.joinToString(" ") else "$prefix$code $epName".trim(),
            "$serial · $code", art(ep.optString("i").ifEmpty { info.optString("i") }),
            MediaMetadata.MEDIA_TYPE_TV_SHOW, durationMs = durationMs(ep), album = serial,
            extra = sosacExtras("episodes", ep, "season/$serialId/$season")
        )
    }

    private fun serialFolder(item: JSONObject, group: String? = null) = folder(
        "serial/${SosacItem.id(item)}", SosacItem.title(item), SosacItem.meta(item), art(item.optString("i")),
        group = group, type = MediaMetadata.MEDIA_TYPE_TV_SHOW
    )

    /** Priecinok so zoznamom zo Sosac; filmy a serialy sa zobrazia ako mriezka plagatov. */
    private fun listFolder(kind: String, name: String, title: String, group: String? = null, param: Pair<String, String>? = null): MediaItem {
        val id = "list/$kind/$name/1" + (param?.let { "/${it.first}/${it.second}" } ?: "")
        return folder(id, title, group = group, browsableStyle = GRID, playableStyle = GRID)
    }

    // ---- strom ----

    fun root(): MediaItem = folder(ROOT, "Médiá")

    /** Potomkovia polozky; zaroven si ich zapamata pre neskorsie zostavenie radu. */
    suspend fun children(parentId: String): List<MediaItem> {
        val list = withContext(Dispatchers.IO) { load(parentId) }
        for (it in list) {
            items[it.mediaId] = it
            parents[it.mediaId] = it.mediaMetadata.extras?.getString(K_PARENT) ?: parentId
        }
        return list
    }

    private suspend fun load(parentId: String): List<MediaItem> {
        val p = parentId.split('/')
        return when (p[0]) {
            ROOT -> listOf(
                folder(MUSIC, "Hudba", art = icon(R.drawable.ic_music)),
                folder(VIDEOS, "Videá", art = icon(R.drawable.ic_video), playableStyle = GRID),
                folder(MOVIES, "Filmy", art = icon(R.drawable.ic_movie)),
                folder(SERIALS, "Seriály", art = icon(R.drawable.ic_tv))
            )
            MUSIC -> when {
                !local.hasAudioPermission -> listOf(infoItem("perm", "Povoľte prístup k hudbe", "V aplikácii v telefóne"))
                p.size == 1 -> listOf(
                    folder("music/artists", "Interpreti", art = icon(R.drawable.ic_folder)),
                    folder("music/albums", "Albumy", art = icon(R.drawable.ic_folder), browsableStyle = GRID),
                    folder("music/songs", "Všetky skladby", art = icon(R.drawable.ic_music))
                )
                p[1] == "artists" -> local.artists().map {
                    folder("artist/${it.id}", it.name, "${it.tracks} skladieb", browsableStyle = GRID, type = MediaMetadata.MEDIA_TYPE_ARTIST)
                }
                p[1] == "albums" -> local.albums().map(::albumFolder)
                p[1] == "songs" -> local.allSongs().map { songItem(it) }
                else -> emptyList()
            }
            "artist" -> local.albums(p[1].toLong()).map(::albumFolder)
            "album" -> local.albumSongs(p[1].toLong()).map { songItem(it) }
            VIDEOS -> if (!local.hasVideoPermission) listOf(infoItem("perm", "Povoľte prístup k videám", "V aplikácii v telefóne"))
            else local.videos().map { videoItem(it) }
            MOVIES -> if (!api.isConfigured) loginInfo() else moviesMenu()
            SERIALS -> if (!api.isConfigured) loginInfo() else serialsMenu()
            "list" -> sosacList(p)
            "pick" -> picker(p[1], p[2], p[3])
            "serial" -> seasons(p[1])
            "season" -> episodes(p[1], p[2])
            else -> emptyList()
        }
    }

    private fun albumFolder(a: LocalMedia.Album) =
        folder("album/${a.id}", a.name, a.artist, ArtworkProvider.album(ctx, a.id), type = MediaMetadata.MEDIA_TYPE_ALBUM)

    private fun loginInfo() = listOf(infoItem("login", "Prihláste sa v aplikácii v telefóne", "Nastavenia: Sosac a Streamuj.tv"))

    private suspend fun moviesMenu(): List<MediaItem> {
        api.init()
        val k = "movies"
        val out = mutableListOf(
            listFolder(k, "queue", "Môj zoznam", "Moje"),
            listFolder(k, "unfinished", "Rozpozerané", "Moje"),
            listFolder(k, "finished", "Videné", "Moje"),
            listFolder(k, "news-with-dubbing", "S dabingom", "Novinky"),
            listFolder(k, "news-with-subtitles", "S titulkami", "Novinky"),
            listFolder(k, "last-added", "Naposledy pridané", "Novinky")
        )
        if (api.topic.isNotEmpty()) out.add(listFolder(k, "current-topic", api.topic.split('|').last(), "Novinky"))
        out += listOf(
            listFolder(k, "news-popular", "Populárne novinky", "Rebríčky"),
            listFolder(k, "popular", "Najpopulárnejšie", "Rebríčky"),
            listFolder(k, "news-top-rated", "Najlepšie novinky", "Rebríčky"),
            listFolder(k, "top-rated", "Najlepšie hodnotené", "Rebríčky"),
            folder("pick/$k/a-z/l", "A – Z", group = "Prehliadať"),
            folder("pick/$k/by-genre/g", "Podľa žánru", group = "Prehliadať"),
            folder("pick/$k/by-quality/q", "Podľa kvality", group = "Prehliadať")
        )
        return out
    }

    private fun serialsMenu(): List<MediaItem> {
        val k = "serials"
        return listOf(
            listFolder(k, "queue", "Môj zoznam", "Moje"),
            listFolder(k, "unfinished", "Rozpozerané", "Moje"),
            listFolder(k, "finished", "Videné", "Moje"),
            listFolder(k, "last-added", "Nové seriály", "Novinky"),
            listFolder("episodes", "news-with-dubbing", "Epizódy s dabingom", "Novinky"),
            listFolder("episodes", "news-with-subtitles", "Epizódy s titulkami", "Novinky"),
            listFolder("episodes", "last-added", "Naposledy pridané epizódy", "Novinky"),
            listFolder(k, "news-popular", "Populárne novinky", "Rebríčky"),
            listFolder(k, "popular", "Najpopulárnejšie", "Rebríčky"),
            listFolder(k, "news-top-rated", "Najlepšie novinky", "Rebríčky"),
            listFolder(k, "top-rated", "Najlepšie hodnotené", "Rebríčky"),
            folder("pick/$k/a-z/l", "A – Z", group = "Prehliadať"),
            folder("pick/$k/by-genre/g", "Podľa žánru", group = "Prehliadať")
        )
    }

    private fun picker(kind: String, name: String, param: String): List<MediaItem> {
        val values = when (param) {
            "l" -> LETTERS.map { it to it.uppercase() }
            "g" -> GENRES
            "q" -> QUALITY_FILTER
            else -> emptyList()
        }
        return values.map { (value, label) -> listFolder(kind, name, label, param = param to value) }
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

    private suspend fun sosacList(p: List<String>): List<MediaItem> {
        val kind = p[1]
        val name = p[2]
        val page = p[3].toIntOrNull() ?: 1
        val params = if (p.size >= 6) mapOf(p[4] to p[5]) else emptyMap()
        val arr = api.list(kind, name, params, page)
        val out = arr.objects().mapNotNull {
            when (kind) {
                "movies" -> movieItem(it)
                "serials" -> serialFolder(it)
                else -> episodeItem(it)
            }
        }.toMutableList()
        if (arr.length() >= SosacApi.PAGE) {
            val next = "list/$kind/$name/${page + 1}" + (if (p.size >= 6) "/${p[4]}/${p[5]}" else "")
            out.add(folder(next, "Ďalšie…", "Strana ${page + 1}", browsableStyle = GRID, playableStyle = GRID))
        }
        if (out.isEmpty()) out.add(infoItem("empty", "Žiadne položky"))
        return out
    }

    private suspend fun serialDetail(id: String): JSONObject =
        serials.get(id) ?: api.detail("serials", id).also { serials.put(id, it) }

    private data class EpisodeRef(val season: String, val number: String, val episode: JSONObject)

    /** Prva nedopozerana epizoda, ak uz je nieco zo serialu videne; inak null. */
    private fun resumeEpisode(detail: JSONObject): EpisodeRef? {
        var anyWatched = false
        var found: EpisodeRef? = null
        for (season in SosacItem.seasons(detail)) {
            val s = detail.optJSONObject(season) ?: continue
            for (num in SosacItem.episodeNumbers(s)) {
                val ep = s.optJSONObject(num) ?: continue
                if (SosacItem.watched(ep) > 0) anyWatched = true
                if (found == null && !SosacItem.isWatched(ep)) found = EpisodeRef(season, num, ep)
            }
        }
        return if (anyWatched) found else null
    }

    private suspend fun seasons(serialId: String): List<MediaItem> {
        val detail = serialDetail(serialId)
        val info = detail.optJSONObject("info") ?: JSONObject()
        val out = mutableListOf<MediaItem>()
        resumeEpisode(detail)?.let { r ->
            seasonEpisodeItem(serialId, info, r.season, r.number, r.episode, "▶ Pokračovať: ")?.let(out::add)
        }
        for (season in SosacItem.seasons(detail)) {
            val s = detail.optJSONObject(season) ?: continue
            val eps = SosacItem.episodeNumbers(s).mapNotNull { s.optJSONObject(it) }
            val watched = eps.count { SosacItem.isWatched(it) }
            out.add(folder("season/$serialId/$season", "Séria $season", "$watched/${eps.size} videných", art(info.optString("i"))))
        }
        if (out.isEmpty()) out.add(infoItem("empty", "Seriál nemá žiadne epizódy"))
        return out
    }

    private suspend fun episodes(serialId: String, season: String): List<MediaItem> {
        val detail = serialDetail(serialId)
        val info = detail.optJSONObject("info") ?: JSONObject()
        val s = detail.optJSONObject(season) ?: return emptyList()
        return SosacItem.episodeNumbers(s).mapNotNull { num ->
            s.optJSONObject(num)?.let { seasonEpisodeItem(serialId, info, season, num, it) }
        }
    }

    // ---- hladanie ----

    suspend fun search(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        searches.get(q)?.let { return it }
        val out = mutableListOf<MediaItem>()
        if (api.isConfigured) {
            try {
                api.search("movies", q, 1).objects().mapNotNullTo(out) { movieItem(it, "Filmy") }
                api.search("serials", q, 1).objects().mapTo(out) { serialFolder(it, "Seriály") }
            } catch (e: IOException) {
                if (out.isEmpty()) out.add(errorItem(e))
            }
        }
        withContext(Dispatchers.IO) {
            if (local.hasAudioPermission) local.searchSongs(q).take(50).mapTo(out) { songItem(it, "Hudba") }
            if (local.hasVideoPermission) local.searchVideos(q).take(50).mapTo(out) { videoItem(it, "Videá") }
        }
        for (it in out) {
            items[it.mediaId] = it
            parents.putIfAbsent(it.mediaId, "search")
        }
        searches.put(q, out)
        return out
    }

    /** Hlasove "pusti X": prva prehratelna polozka, pri seriali epizoda, kde sa skoncilo. */
    private suspend fun searchPlayable(query: String): String? {
        val results = search(query)
        val first = results.firstOrNull { it.mediaMetadata.isPlayable == true || it.mediaId.startsWith("serial/") }
            ?: return null
        if (!first.mediaId.startsWith("serial/")) return first.mediaId
        val serialId = first.mediaId.removePrefix("serial/")
        val detail = serialDetail(serialId)
        val info = detail.optJSONObject("info") ?: JSONObject()
        val ref = resumeEpisode(detail) ?: SosacItem.seasons(detail).firstNotNullOfOrNull { season ->
            val s = detail.optJSONObject(season)
            s?.let { SosacItem.episodeNumbers(it).firstOrNull()?.let { n -> EpisodeRef(season, n, s.getJSONObject(n)) } }
        } ?: return null
        val item = seasonEpisodeItem(serialId, info, ref.season, ref.number, ref.episode) ?: return null
        items[item.mediaId] = item
        parents[item.mediaId] = "season/$serialId/${ref.season}"
        return item.mediaId
    }

    // ---- prehravanie ----

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        ROOT -> root()
        else -> items[mediaId] ?: fetchItem(mediaId)
    }

    private suspend fun fetchItem(mediaId: String): MediaItem? {
        val p = mediaId.split('/')
        if (p.size < 2) return null
        val item = withContext(Dispatchers.IO) {
            when (p[0]) {
                "song" -> p[1].toLongOrNull()?.let { local.song(it) }?.let { songItem(it) }
                "video" -> p[1].toLongOrNull()?.let { local.video(it) }?.let { videoItem(it) }
                "play" -> if (p.size < 3) null else when (p[1]) {
                    "movies" -> movieItem(api.detail("movies", p[2]))
                    "episodes" -> episodeItem(api.detail("episodes", p[2]))
                    else -> null
                }
                else -> null
            }
        }
        if (item != null) items[mediaId] = item
        return item
    }

    /** Sosac polozke doplni URI pre ResolvingDataSource; v aute s nizsou kvalitou (obraz sa aj tak nezobrazuje). */
    private fun withUri(item: MediaItem, car: Boolean): MediaItem {
        val ex = item.mediaMetadata.extras ?: return item
        val kind = ex.getString(K_KIND) ?: return item
        val uri = StreamResolver.uri(
            kind, ex.getString(K_ID) ?: "", ex.getString(K_LINK) ?: "",
            if (car) prefs.carQuality else prefs.quality
        )
        return item.buildUpon().setUri(uri).setMimeType(MimeTypes.VIDEO_MP4).build()
    }

    private fun resumeMs(item: MediaItem): Long {
        val sec = resumeOverride[item.mediaId] ?: item.mediaMetadata.extras?.getDouble(K_RESUME) ?: 0.0
        return (sec * 1000).toLong()
    }

    private fun expandable(parent: String) =
        parent.startsWith("album/") || parent == "music/songs" || (parent.startsWith("season/") && prefs.nextEpisode)

    /** Pre skladbu z albumu alebo epizodu zo serie vrati celu skupinu, aby sa pokracovalo dalsou. */
    private suspend fun siblings(mediaId: String): Pair<List<MediaItem>, Int>? {
        val parent = parents[mediaId] ?: return null
        if (!expandable(parent)) return null
        val list = children(parent).filter { it.mediaMetadata.isPlayable == true }
        val index = list.indexOfFirst { it.mediaId == mediaId }
        return if (index < 0) null else list to index
    }

    suspend fun queue(requested: List<MediaItem>, startIndex: Int, startPositionMs: Long, car: Boolean): MediaItemsWithStartPosition {
        if (requested.size == 1) {
            val req = requested[0]
            val query = req.requestMetadata.searchQuery
            val id = if (req.mediaId.isEmpty() && !query.isNullOrBlank()) {
                searchPlayable(query) ?: throw IOException("Pre „$query“ sa nič nenašlo")
            } else req.mediaId
            val group = siblings(id)
            val list = group?.first ?: listOfNotNull(item(id)?.takeIf { it.mediaMetadata.isPlayable == true })
            if (list.isEmpty()) throw IOException("Položku nie je možné prehrať")
            val index = group?.second ?: 0
            val playables = list.map { withUri(it, car) }
            val position = if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) startPositionMs else resumeMs(playables[index])
            return MediaItemsWithStartPosition(playables, index, position)
        }
        val list = requested.mapNotNull { r -> item(r.mediaId)?.takeIf { it.mediaMetadata.isPlayable == true }?.let { withUri(it, car) } }
        if (list.isEmpty()) throw IOException("Položky nie je možné prehrať")
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, list.size - 1)
        val position = if (startPositionMs == C.TIME_UNSET) resumeMs(list[index]) else startPositionMs
        return MediaItemsWithStartPosition(list, index, position)
    }

    suspend fun resolveForQueue(requested: List<MediaItem>, car: Boolean): List<MediaItem> =
        requested.mapNotNull { r -> item(r.mediaId)?.takeIf { it.mediaMetadata.isPlayable == true }?.let { withUri(it, car) } }

    /** Po prehravani si zapamata novu poziciu, aby dalsie spustenie pokracovalo spravne. */
    fun updateWatched(mediaId: String, seconds: Double, durationSec: Double) {
        val resume = if (seconds < 30 || (durationSec > 0 && seconds > durationSec * 0.95)) 0.0 else seconds
        resumeOverride[mediaId] = resume
        episodeJson[mediaId]?.put("w", seconds)
    }
}
