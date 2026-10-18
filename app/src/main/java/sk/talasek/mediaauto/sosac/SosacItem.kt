package sk.talasek.mediaauto.sosac

import org.json.JSONArray
import org.json.JSONObject

/** Pomocne funkcie nad polozkami zo Sosac API (skratene nazvy poli ako v API). */
object SosacItem {

    private fun first(v: Any?): String? = when (v) {
        is JSONArray -> v.optString(0).takeIf { it.isNotEmpty() }
        is String -> v.takeIf { it.isNotEmpty() }
        else -> null
    }

    /** Nazov v poradi cs, sk, en, us. */
    fun name(n: JSONObject?): String {
        if (n == null) return ""
        return first(n.opt("cs")) ?: first(n.opt("sk")) ?: first(n.opt("en")) ?: first(n.opt("us")) ?: ""
    }

    fun title(item: JSONObject) = name(item.optJSONObject("n"))

    fun episodeName(item: JSONObject) = name(item.optJSONObject("ne"))

    fun id(item: JSONObject) = item.optString("_id")

    fun link(item: JSONObject) = item.optString("l")

    /** Zastupny obrazok servera (biela filmova cievka) radsej nezobrazujeme. */
    fun image(url: String?): String? =
        url?.takeIf { it.isNotBlank() && !it.contains("defaultni") }?.replace(Regex("^http:"), "https:")

    fun poster(item: JSONObject) = image(item.optString("i"))

    fun pad(n: Int) = if (n < 10) "0$n" else "$n"

    fun episodeCode(item: JSONObject): String =
        if (item.has("s") && item.has("ep")) "${item.optInt("s")}x${pad(item.optInt("ep"))}" else ""

    fun dubbing(item: JSONObject): String {
        val d = item.optJSONArray("d") ?: return ""
        return (0 until d.length()).joinToString(", ") { d.optString(it) }.uppercase().replace("TIT", " tit.")
    }

    fun watched(item: JSONObject) = item.optDouble("w", 0.0).takeIf { !it.isNaN() } ?: 0.0

    fun duration(item: JSONObject) = item.optDouble("dl", 0.0).takeIf { !it.isNaN() } ?: 0.0

    fun isWatched(item: JSONObject): Boolean {
        val w = watched(item)
        val dl = duration(item)
        return w > 0 && dl > 0 && w > dl * 0.95
    }

    /** Podiel pozretia 0..1. */
    fun progress(item: JSONObject): Double {
        if (isWatched(item)) return 1.0
        val w = watched(item)
        val dl = duration(item)
        return if (w > 0 && dl > 0) minOf(1.0, w / dl) else 0.0
    }

    /** Pozicia, od ktorej pokracovat (0 = od zaciatku). */
    fun resumeSeconds(item: JSONObject): Double {
        val w = watched(item)
        return if (w < 30 || isWatched(item)) 0.0 else w
    }

    fun meta(item: JSONObject): String {
        val parts = mutableListOf<String>()
        if (item.optInt("y") > 0) parts.add(item.optInt("y").toString())
        val dl = duration(item)
        if (dl > 0) parts.add("${Math.round(dl / 60)} min")
        dubbing(item).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }

    /** Cisla serii zo detailu serialu (kluce "1", "2", ...), zoradene. */
    fun seasons(detail: JSONObject): List<String> =
        detail.keys().asSequence().filter { it.matches(Regex("\\d+")) }.sortedBy { it.toInt() }.toList()

    fun episodeNumbers(season: JSONObject): List<String> =
        season.keys().asSequence().filter { it.matches(Regex("\\d+")) }.sortedBy { it.toInt() }.toList()
}
