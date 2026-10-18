package sk.talasek.mediaauto.library

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import sk.talasek.mediaauto.sosac.SosacApi
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Obrazky pre Android Auto (a zoznamy v telefone). Auto nacitava obrazky len cez content:// URI,
 * preto plagaty zo Sosac stiahneme do cache a obaly albumov / nahlady videi vyrobime z MediaStore.
 *
 *   content://<app>.art/web?u=<https url>
 *   content://<app>.art/album/<id>
 *   content://<app>.art/video/<id>
 */
class ArtworkProvider : ContentProvider() {

    companion object {
        private const val MAX_BYTES = 5 * 1024 * 1024

        private fun base(context: Context) = Uri.Builder().scheme("content").authority(context.packageName + ".art")

        fun web(context: Context, url: String?): Uri? =
            url?.let { base(context).appendPath("web").appendQueryParameter("u", it).build() }

        fun album(context: Context, id: Long): Uri = base(context).appendPath("album").appendPath(id.toString()).build()

        fun video(context: Context, id: Long): Uri = base(context).appendPath("video").appendPath(id.toString()).build()

        fun resource(context: Context, resId: Int): Uri = Uri.Builder().scheme("android.resource")
            .authority(context.packageName).appendPath(context.resources.getResourceTypeName(resId))
            .appendPath(context.resources.getResourceEntryName(resId)).build()
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
        val file = File(dir, SosacApi.md5(uri.toString()))
        if (!file.exists()) {
            val tmp = File(dir, file.name + "." + System.nanoTime() + ".tmp")
            try {
                when (uri.pathSegments.firstOrNull()) {
                    "web" -> download(uri.getQueryParameter("u"), tmp)
                    "album" -> thumbnail(ctx, ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, id(uri)), tmp)
                    "video" -> thumbnail(ctx, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id(uri)), tmp)
                    else -> throw FileNotFoundException(uri.toString())
                }
                if (!tmp.renameTo(file) && !file.exists()) throw FileNotFoundException(uri.toString())
            } catch (e: FileNotFoundException) {
                throw e
            } catch (e: Exception) {
                throw FileNotFoundException(e.message)
            } finally {
                tmp.delete()
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun id(uri: Uri) = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())

    private fun download(url: String?, to: File) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) throw FileNotFoundException(url)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = SosacApi.TIMEOUT
            c.readTimeout = SosacApi.TIMEOUT
            c.setRequestProperty("User-Agent", SosacApi.USER_AGENT)
            if (c.responseCode != 200 || c.contentType?.startsWith("image/") != true) throw FileNotFoundException(url)
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw FileNotFoundException("too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun thumbnail(ctx: Context, uri: Uri, to: File) {
        val bitmap = ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null)
        to.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
