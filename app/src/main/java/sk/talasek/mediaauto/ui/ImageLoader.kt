package sk.talasek.mediaauto.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Male obrazky do zoznamu; nacitava ich cez ArtworkProvider (rovnako ako Android Auto). */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(scope: CoroutineScope, view: ImageView, uri: Uri?, placeholder: Int) {
        view.tag = uri
        if (uri == null) {
            view.setImageResource(placeholder)
            return
        }
        if (uri.scheme == "android.resource") {
            view.setImageURI(uri)
            return
        }
        val key = uri.toString()
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageResource(placeholder)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(view.context, uri) } ?: return@launch
            cache.put(key, bitmap)
            if (view.tag == uri) view.setImageBitmap(bitmap)
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 200 && bounds.outHeight / (sample * 2) >= 200) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }
}
