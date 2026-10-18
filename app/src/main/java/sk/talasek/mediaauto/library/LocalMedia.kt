package sk.talasek.mediaauto.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** Lokalna hudba a videa z MediaStore. */
class LocalMedia(private val context: Context) {

    data class Artist(val id: Long, val name: String, val tracks: Int)
    data class Album(val id: Long, val name: String, val artist: String)
    data class Song(val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val durationMs: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    }
    data class Video(val id: Long, val title: String, val folder: String, val durationMs: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val hasAudioPermission: Boolean
        get() = granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)

    val hasVideoPermission: Boolean
        get() = granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun <T> query(
        uri: Uri, columns: Array<String>, selection: String?, args: Array<String>?, order: String?,
        map: (android.database.Cursor) -> T
    ): List<T> {
        val out = mutableListOf<T>()
        context.contentResolver.query(uri, columns, selection, args, order)?.use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    fun artists(): List<Artist> = query(
        MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Artists._ID, MediaStore.Audio.Artists.ARTIST, MediaStore.Audio.Artists.NUMBER_OF_TRACKS),
        null, null, MediaStore.Audio.Artists.ARTIST + " COLLATE LOCALIZED"
    ) { Artist(it.getLong(0), it.getString(1) ?: "?", it.getInt(2)) }

    fun albums(artistId: Long? = null): List<Album> = query(
        if (artistId != null) MediaStore.Audio.Artists.Albums.getContentUri("external", artistId)
        else MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM, MediaStore.Audio.Albums.ARTIST),
        null, null, MediaStore.Audio.Albums.ALBUM + " COLLATE LOCALIZED"
    ) { Album(it.getLong(0), it.getString(1) ?: "?", it.getString(2) ?: "") }

    private val songColumns = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION
    )

    private fun songs(selection: String, args: Array<String>?, order: String): List<Song> = query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songColumns,
        MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " + selection, args, order
    ) { Song(it.getLong(0), it.getString(1) ?: "?", it.getString(2) ?: "", it.getString(3) ?: "", it.getLong(4), it.getLong(5)) }

    fun allSongs() = songs("1", null, MediaStore.Audio.Media.TITLE + " COLLATE LOCALIZED")

    fun albumSongs(albumId: Long) = songs(
        MediaStore.Audio.Media.ALBUM_ID + " = ?", arrayOf(albumId.toString()),
        MediaStore.Audio.Media.TRACK + ", " + MediaStore.Audio.Media.TITLE
    )

    fun song(id: Long) = songs(MediaStore.Audio.Media._ID + " = ?", arrayOf(id.toString()), MediaStore.Audio.Media._ID).firstOrNull()

    fun searchSongs(q: String): List<Song> {
        val like = "%$q%"
        return songs(
            "(" + MediaStore.Audio.Media.TITLE + " LIKE ? OR " + MediaStore.Audio.Media.ARTIST + " LIKE ? OR " +
                MediaStore.Audio.Media.ALBUM + " LIKE ?)",
            arrayOf(like, like, like), MediaStore.Audio.Media.TITLE + " COLLATE LOCALIZED"
        )
    }

    private val videoColumns = arrayOf(
        MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DURATION
    )

    private fun videos(selection: String?, args: Array<String>?): List<Video> = query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoColumns, selection, args,
        MediaStore.Video.Media.DATE_ADDED + " DESC"
    ) { Video(it.getLong(0), it.getString(1) ?: "?", it.getString(2) ?: "", it.getLong(3)) }

    fun videos() = videos(null, null)

    fun video(id: Long) = videos(MediaStore.Video.Media._ID + " = ?", arrayOf(id.toString())).firstOrNull()

    fun searchVideos(q: String) = videos(MediaStore.Video.Media.TITLE + " LIKE ?", arrayOf("%$q%"))
}
