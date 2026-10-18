package sk.talasek.mediaauto

import android.app.Application
import android.content.Context
import sk.talasek.mediaauto.download.Downloads
import sk.talasek.mediaauto.library.Library
import sk.talasek.mediaauto.library.LocalMedia
import sk.talasek.mediaauto.sosac.SosacApi
import sk.talasek.mediaauto.sosac.StreamResolver

class App : Application() {
    val prefs by lazy { Prefs(this) }
    val api by lazy { SosacApi(prefs) }
    val streams by lazy { StreamResolver(api, prefs) }
    val downloads by lazy { Downloads(this, prefs, streams) }
    val library by lazy { Library(this, api, prefs, LocalMedia(this), downloads) }

    companion object {
        fun get(context: Context) = context.applicationContext as App
    }
}
