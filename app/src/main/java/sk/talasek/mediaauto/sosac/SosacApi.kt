package sk.talasek.mediaauto.sosac

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import sk.talasek.mediaauto.Prefs
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class SosacException(val code: Int, message: String) : IOException(message)

/**
 * Klient pre obe sluzby: sosac (metadata, zoznamy, cas sledovania) a streamuj.tv (ulozisko videi).
 * Protokol je prevzaty z Kodi doplnku plugin.video.sosac-2 (cez sosac-tizen).
 */
class SosacApi(private val prefs: Prefs) {

    companion object {
        const val PAGE = 48
        private const val SALT = "EWs5yVD4QF2sshGm22EWVa"
        private val SETTINGS_URLS = listOf(
            "https://kodi-api.sosac.to/settings",
            "https://tv.sosac.ph/settings.json",
            "https://sosac.eu/settings.json"
        )
        const val TIMEOUT = 20_000
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) MediaAuto/1.0"

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun sosacHash(user: String, pass: String) = md5(md5("$user:$pass") + SALT)

        fun streamujHash(pass: String) = md5(pass)
    }

    @Volatile var domain = "kodi-api.sosac.to"; private set
    @Volatile var provider = "www.streamuj.tv"; private set
    @Volatile var topic = ""; private set

    private val initLock = Mutex()
    private var initialized = false

    val isConfigured get() = prefs.sosacUser.isNotEmpty() && prefs.streamujUser.isNotEmpty()

    private class Response(val status: Int, val text: String)

    private fun request(method: String, url: String, body: String? = null): Response {
        val c = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("Chybná adresa", e)
        }
        try {
            c.requestMethod = method
            c.connectTimeout = TIMEOUT
            c.readTimeout = TIMEOUT
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            return Response(status, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("Server neodpovedá", e)
        } catch (e: SosacException) {
            throw e
        } catch (e: IOException) {
            throw IOException("Chyba siete", e)
        } finally {
            c.disconnect()
        }
    }

    private fun query(params: Map<String, Any?>) = params.entries
        .filter { it.value != null }
        .joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value.toString(), "UTF-8") }

    private fun parse(res: Response): Any {
        val json = try {
            JSONTokener(res.text).nextValue()
        } catch (e: Exception) {
            null
        }
        val code = (json as? JSONObject)?.optInt("code")
        if (res.status == 401 || code == 401) throw SosacException(401, "Neplatné prihlásenie na Sosac")
        if (res.status >= 400 || (json !is JSONObject && json !is JSONArray)) {
            val msg = (json as? JSONObject)?.optString("message")?.takeIf { it.isNotEmpty() }
            throw SosacException(res.status, msg ?: "Chyba servera (${res.status})")
        }
        return json
    }

    // ---- sosac ----

    private fun sosacUrl(path: String, params: Map<String, Any?> = emptyMap()): String {
        val p = params + mapOf("username" to prefs.sosacUser, "password" to prefs.sosacHash)
        return "https://$domain/$path?${query(p)}"
    }

    private suspend fun sosacGet(path: String, params: Map<String, Any?> = emptyMap()): Any {
        init()
        return withContext(Dispatchers.IO) { parse(request("GET", sosacUrl(path, params))) }
    }

    private fun Any.asArray(): JSONArray = this as? JSONArray ?: JSONArray()

    /** Zisti aktualnu domenu API a uloziska; pri vypadku skusa zalozne adresy. */
    suspend fun init() {
        initLock.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                for (url in SETTINGS_URLS) {
                    try {
                        val d = JSONObject(request("GET", url).text)
                        val dom = d.optString("domain")
                        val prov = d.optString("streaming_provider")
                        if (dom.isEmpty() || prov.isEmpty()) continue
                        domain = dom
                        provider = prov
                        topic = d.optString("current_topic")
                        break
                    } catch (e: Exception) {
                        // dalsia adresa
                    }
                }
            }
            initialized = true
        }
    }

    /** kind: movies | serials | episodes */
    suspend fun list(kind: String, name: String, params: Map<String, String>, page: Int, count: Int = PAGE): JSONArray =
        sosacGet("$kind/lists/$name", params + mapOf("pocet" to count, "stranka" to page)).asArray()

    suspend fun search(kind: String, q: String, page: Int, count: Int = PAGE): JSONArray =
        sosacGet("$kind/simple-search", mapOf("q" to q, "pocet" to count, "stranka" to page)).asArray()

    suspend fun detail(kind: String, id: String): JSONObject =
        sosacGet("$kind/$id") as? JSONObject ?: throw SosacException(0, "Neplatná odpoveď servera")

    /** kind: movies | episodes */
    suspend fun setWatchTime(kind: String, id: String, seconds: Long) {
        init()
        withContext(Dispatchers.IO) {
            try {
                request("POST", sosacUrl("$kind/$id/watching-time", mapOf("d" to 19)), JSONObject().put("time", seconds).toString())
            } catch (e: IOException) {
                // nekriticke
            }
        }
    }

    suspend fun checkSosac() {
        sosacGet("movies/lists/queue", mapOf("pocet" to 1, "stranka" to 1))
    }

    // ---- streamuj.tv ----

    private fun streamujUrl(params: Map<String, Any?>) = "https://$provider/json_api_player.php?${query(params)}"

    /** result: 0 = zle prihlasenie, 1 = ucet bez premium, 2 = premium (expiration) */
    suspend fun checkStreamuj(): JSONObject {
        init()
        return withContext(Dispatchers.IO) {
            parse(request("GET", streamujUrl(mapOf(
                "action" to "check-user",
                "login" to prefs.streamujUser,
                "password" to prefs.streamujHash,
                "passwordinmd5" to 1
            )))) as? JSONObject ?: JSONObject()
        }
    }

    /** Vrati { URL: { CZ: { HD: url, FHD: url, subtitles: { cz: url } }, ... }, errormessage } */
    suspend fun links(linkId: String): JSONObject {
        init()
        return withContext(Dispatchers.IO) {
            parse(request("GET", streamujUrl(mapOf(
                "action" to "get-video-links",
                "d" to 19,
                "link" to linkId,
                "login" to prefs.streamujUser,
                "password" to prefs.streamujHash,
                "location" to prefs.location
            )))) as? JSONObject ?: JSONObject()
        }
    }

    /** Velkost suboru v bajtoch (HEAD, pripadne prvy bajt cez Range), null ak ju server neprezradi. */
    suspend fun contentLength(url: String): Long? = withContext(Dispatchers.IO) {
        fun open(method: String, range: Boolean): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            setRequestProperty("User-Agent", USER_AGENT)
            if (range) setRequestProperty("Range", "bytes=0-0")
        }
        val head = open("HEAD", false)
        try {
            if (head.responseCode in 200..299 && head.contentLengthLong > 0) return@withContext head.contentLengthLong
        } catch (e: IOException) {
            // skusime Range
        } finally {
            head.disconnect()
        }
        val c = open("GET", true)
        try {
            c.responseCode
            c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        } catch (e: IOException) {
            null
        } finally {
            c.disconnect()
        }
    }

    /**
     * Odkaz zo streamuj vracia v tele adresu samotneho suboru. Keby server niekedy
     * poslal rovno video, citanie prerusime a pouzijeme konecnu adresu po presmerovani.
     */
    suspend fun resolve(link: String): String = withContext(Dispatchers.IO) {
        val c = URL(link).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT
            c.readTimeout = TIMEOUT
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.responseCode
            val type = c.contentType ?: ""
            if (Regex("^(video|audio)/|octet-stream|mpegurl", RegexOption.IGNORE_CASE).containsMatchIn(type)) {
                return@withContext c.url.toString()
            }
            val text = c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(4096)
                val n = r.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }.trim()
            if (Regex("^https?://\\S+$").matches(text)) text else link
        } catch (e: IOException) {
            link
        } finally {
            c.disconnect()
        }
    }
}
