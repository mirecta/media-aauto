package sk.talasek.mediaauto.sosac

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import sk.talasek.mediaauto.Prefs
import java.io.IOException

/**
 * Prevod odkazu Sosac videa na adresu suboru zo streamuj.tv.
 *
 * Polozky v rade prehravaca maju URI `sosac://<kind>/<id>/<link>?q=<kvalita>` a skutocna adresa
 * sa zisti az pri otvoreni (ResolvingDataSource), takze cela seria moze byt v rade bez toho,
 * aby sa dopredu volalo streamuj.tv pre kazdu epizodu.
 */
class StreamResolver(private val api: SosacApi, private val prefs: Prefs) {

    companion object {
        const val SCHEME = "sosac"
        val QUALITIES = listOf("UHD HDR", "UHD", "FHD", "HD", "SD")

        fun uri(kind: String, id: String, link: String, quality: String): Uri = Uri.Builder()
            .scheme(SCHEME).authority(kind).appendPath(id).appendPath(link)
            .appendQueryParameter("q", quality).build()

        private fun rank(q: String) = QUALITIES.indexOf(q).let { if (it < 0) QUALITIES.size else it }
    }

    private data class Stream(val lang: String, val quality: String, val url: String)

    /**
     * Volane z vlakna nacitavania prehravaca pri kazdom otvoreni (aj pri posune). Adresu si nepamatame:
     * subor zo streamuj ma v adrese token, ktory po pouziti prestane platit a server potom
     * namiesto videa vrati text "invalid token".
     */
    fun resolveBlocking(uri: Uri): Uri {
        val link = uri.pathSegments.getOrNull(1) ?: throw IOException("Neplatný odkaz na video")
        val quality = uri.getQueryParameter("q") ?: prefs.quality
        val url = try {
            runBlocking { resolve(link, quality) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Chyba pri získavaní streamu", e)
        }
        return Uri.parse(url)
    }

    private suspend fun resolve(link: String, quality: String): String {
        if (!api.isConfigured) throw SosacException(401, "Zadajte prihlasovacie údaje v aplikácii v telefóne")
        val data = api.links(link)
        val streams = flatten(data)
        if (streams.isEmpty()) {
            val msg = data.optString("errormessage").takeIf { it.isNotEmpty() }
            throw SosacException(0, msg ?: "Pre toto video nie sú dostupné žiadne streamy")
        }
        return api.resolve(autoStream(streams, quality).url)
    }

    private fun flatten(data: JSONObject): List<Stream> {
        val out = mutableListOf<Stream>()
        val urls = data.optJSONObject("URL") ?: return out
        for (lang in urls.keys()) {
            val byQuality = urls.optJSONObject(lang) ?: continue
            for (q in byQuality.keys()) {
                val url = byQuality.opt(q)
                if (q != "subtitles" && url is String) out.add(Stream(lang, q, url))
            }
        }
        return out
    }

    /** Preferovany dabing a v nom najlepsia kvalita, ktora neprekracuje nastavenu. */
    private fun autoStream(streams: List<Stream>, quality: String): Stream {
        val prefsLangs = listOf(prefs.dub1, prefs.dub2)
        var lang = streams[0].lang
        for (p in prefsLangs.reversed()) {
            streams.firstOrNull { it.lang.uppercase() == p }?.let { lang = it.lang }
        }
        val sameLang = streams.filter { it.lang == lang }.sortedBy { rank(it.quality) }
        val wanted = rank(quality)
        return sameLang.firstOrNull { rank(it.quality) >= wanted } ?: sameLang.last()
    }
}
