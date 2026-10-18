package sk.talasek.mediaauto

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Nastavenia aplikacie. Hesla sa ukladaju len ako hash (rovnako ako v Kodi a Tizen verzii). */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun string(key: String, default: String) = object : ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getString(key, default) ?: default
        override fun setValue(thisRef: Any, property: KProperty<*>, value: String) = sp.edit().putString(key, value).apply()
    }

    private fun bool(key: String, default: Boolean) = object : ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getBoolean(key, default)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    }

    private fun long(key: String, default: Long) = object : ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getLong(key, default)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Long) = sp.edit().putLong(key, value).apply()
    }

    var sosacUser by string("sosacUser", "")
    var sosacHash by string("sosacHash", "")
    var streamujUser by string("streamujUser", "")
    var streamujHash by string("streamujHash", "")
    var location by string("location", "1")
    /** Kvalita pri prehravani v telefone. */
    var quality by string("quality", "FHD")
    /** Kvalita v aute: obraz sa nezobrazuje, takze staci najmensi subor. */
    var carQuality by string("carQuality", "SD")
    var dub1 by string("dub1", "CZ")
    var dub2 by string("dub2", "SK")
    var nextEpisode by bool("nextEpisode", true)
    /** Stahovanie do telefonu na offline prehravanie. */
    var downloadQuality by string("downloadQuality", "HD")
    var downloadWifiOnly by bool("downloadWifiOnly", true)

    /** Posledna prehravana polozka, pre obnovenie prehravania (napr. tlacidlo Play v aute). */
    var lastMediaId by string("lastMediaId", "")
    var lastPositionMs by long("lastPositionMs", 0)
}
