package sk.talasek.mediaauto.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.session.MediaBrowser
import kotlinx.coroutines.guava.await

/**
 * Vyber filmov, serialov a videi priamo v Médiá Video. Pouziva ten isty strom ako Médiá Auto
 * (cez MediaBrowser), po vybere sa video spusti a obrazovka sa vrati na obraz.
 *
 * @param parentId ID polozky v Library alebo null pre uvodne menu; [query] pre vysledky hladania.
 */
class BrowseScreen(
    carContext: CarContext,
    private val browser: MediaBrowser,
    private val parentId: String?,
    private val title: String,
    private val play: (MediaItem) -> Unit,
    private val query: String? = null
) : Screen(carContext) {

    companion object {
        private val icons = LruCache<String, Bitmap>(80)

        /** Uvodne menu: to najcastejsie pre obraz v aute. */
        private val START = listOf(
            "list/movies/unfinished/1" to "Rozpozerané filmy",
            "list/serials/unfinished/1" to "Rozpozerané seriály",
            "downloads" to "Stiahnuté",
            "list/movies/queue/1" to "Môj zoznam filmov",
            "list/serials/queue/1" to "Môj zoznam seriálov",
            "movies" to "Všetky filmy",
            "serials" to "Všetky seriály",
            "videos" to "Videá v telefóne"
        )
    }

    private var items: List<MediaItem>? = null
    private var error: String? = null

    init {
        if (parentId != null || query != null) load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val result = if (query != null) browser.getSearchResult(query, 0, Int.MAX_VALUE, null).await()
                else browser.getChildren(parentId!!, 0, Int.MAX_VALUE, null).await()
                // Hudba do obrazu nepatri.
                items = result.value.orEmpty().filter { !it.mediaId.startsWith("song/") && it.mediaId != "music" }
            } catch (e: Exception) {
                error = e.message ?: "Chyba"
            }
            invalidate()
            loadIcons()
        }
    }

    /** Plagaty sa nacitaju dodatocne (cez ArtworkProvider ako v telefone). */
    private suspend fun loadIcons() {
        val uris = items.orEmpty().take(limit()).mapNotNull { it.mediaMetadata.artworkUri }
            .filter { it.scheme == "content" && icons.get(it.toString()) == null }
        if (uris.isEmpty()) return
        var changed = false
        for (uri in uris) {
            val bmp = withContext(Dispatchers.IO) { decode(uri) } ?: continue
            icons.put(uri.toString(), bmp)
            changed = true
        }
        if (changed) invalidate()
    }

    private fun decode(uri: Uri): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        carContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 128 && opts.outHeight / (sample * 2) >= 128) sample *= 2
        carContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    private fun limit() = runCatching {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6)

    private fun image(item: MediaItem): CarIcon? {
        val uri = item.mediaMetadata.artworkUri ?: return null
        if (uri.scheme == "android.resource") {
            // Ikona z aplikacie Médiá Auto.
            val pkg = uri.authority ?: return null
            val res = runCatching { carContext.packageManager.getResourcesForApplication(pkg) }.getOrNull() ?: return null
            val id = res.getIdentifier(uri.lastPathSegment, "drawable", pkg)
            return if (id != 0) CarIcon.Builder(IconCompat.createWithResource(res, pkg, id)).build() else null
        }
        return icons.get(uri.toString())?.let { CarIcon.Builder(IconCompat.createWithBitmap(it)).build() }
    }

    private fun row(item: MediaItem): Row {
        val meta = item.mediaMetadata
        val b = Row.Builder().setTitle(meta.title?.toString()?.ifEmpty { " " } ?: " ")
        meta.subtitle?.toString()?.takeIf { it.isNotEmpty() }?.let { b.addText(it) }
        val ex = meta.extras
        if (ex?.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, -1) == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED) {
            val pct = (ex.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE) * 100).toInt()
            if (pct > 0) b.addText("Rozpozerané $pct %")
        }
        image(item)?.let { b.setImage(it, Row.IMAGE_TYPE_LARGE) }
        when {
            meta.isPlayable == true -> b.setOnClickListener { play(item) }
            meta.isBrowsable == true && !item.mediaId.startsWith("info/") -> {
                b.setBrowsable(true)
                b.setOnClickListener {
                    screenManager.push(BrowseScreen(carContext, browser, item.mediaId, meta.title?.toString() ?: "", play))
                }
            }
        }
        return b.build()
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (parentId == null && query == null) {
            list.addItem(Row.Builder().setTitle("Hľadať").setBrowsable(true).setOnClickListener {
                screenManager.push(SearchScreen(carContext, browser, play))
            }.build())
            START.forEach { (id, label) ->
                list.addItem(Row.Builder().setTitle(label).setBrowsable(true).setOnClickListener {
                    screenManager.push(BrowseScreen(carContext, browser, id, label, play))
                }.build())
            }
            return wrap(ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK).setSingleList(list.build()).build())
        }
        val current = items
        if (current == null && error == null) {
            return wrap(ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK).setLoading(true).build())
        }
        if (error != null) list.addItem(Row.Builder().setTitle(error!!).build())
        else if (current!!.isEmpty()) list.setNoItemsMessage("Žiadne položky")
        else current.take(limit()).forEach { list.addItem(row(it)) }
        return wrap(ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK).setSingleList(list.build()).build())
    }

    /** Navigacne aplikacie mozu zoznam zobrazit len ponad svoju plochu (tu ponad video). */
    private fun wrap(list: ListTemplate): Template =
        if (carContext.carAppApiLevel >= 7) MapWithContentTemplate.Builder().setContentTemplate(list).build() else list
}

/** Hladanie filmov a serialov (klavesnica je dostupna len ked auto stoji). */
class SearchScreen(carContext: CarContext, private val browser: MediaBrowser, private val play: (MediaItem) -> Unit) : Screen(carContext) {
    override fun onGetTemplate(): Template = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
        override fun onSearchSubmitted(searchText: String) {
            if (searchText.isNotBlank()) {
                screenManager.push(BrowseScreen(carContext, browser, null, "Hľadanie: $searchText", play, searchText.trim()))
            }
        }
    }).setHeaderAction(Action.BACK).setSearchHint("Názov filmu alebo seriálu").setShowKeyboardByDefault(true).build()
}
