package sk.talasek.mediaauto.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import sk.talasek.mediaauto.PlaybackService
import sk.talasek.mediaauto.R
import sk.talasek.mediaauto.library.Library

/** Prehliadanie toho isteho stromu, ktory vidi Android Auto, a spustenie prehravania v telefone. */
class MainActivity : AppCompatActivity() {

    private data class Level(val id: String, val title: String, val search: String? = null)

    private val stack = ArrayDeque<Level>()
    private var browser: MediaBrowser? = null
    private lateinit var toolbar: MaterialToolbar
    private lateinit var status: TextView
    private lateinit var nowPlaying: View
    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingToggle: ImageButton
    private val adapter = Adapter()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (stack.size > 1) {
                stack.removeLast()
                reload()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateNowPlaying()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        status = findViewById(R.id.status)
        nowPlaying = findViewById(R.id.now_playing)
        nowPlayingTitle = findViewById(R.id.now_playing_title)
        nowPlayingToggle = findViewById(R.id.now_playing_toggle)
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        toolbar.inflateMenu(R.menu.main)
        toolbar.setNavigationOnClickListener { back.handleOnBackPressed() }
        toolbar.setOnMenuItemClickListener(::onMenu)
        (toolbar.menu.findItem(R.id.search).actionView as SearchView).setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                if (query.isNotBlank()) open(Level("search", "Hľadanie: $query", query.trim()))
                return true
            }

            override fun onQueryTextChange(newText: String) = false
        })
        nowPlaying.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }
        nowPlayingToggle.setOnClickListener {
            browser?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        onBackPressedDispatcher.addCallback(this, back)
        stack.add(Level(Library.ROOT, getString(R.string.app_name)))
        requestPermissions()
    }

    private fun requestPermissions() {
        val wanted = if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        lifecycleScope.launch {
            val b = MediaBrowser.Builder(this@MainActivity, token).buildAsync().await()
            browser = b
            b.addListener(playerListener)
            updateNowPlaying()
            reload()
        }
    }

    override fun onStop() {
        browser?.release()
        browser = null
        super.onStop()
    }

    private fun onMenu(item: MenuItem): Boolean {
        if (item.itemId == R.id.settings) startActivity(Intent(this, SettingsActivity::class.java))
        return true
    }

    private fun open(level: Level) {
        stack.addLast(level)
        reload()
    }

    private fun reload() {
        val level = stack.last()
        toolbar.title = level.title
        toolbar.navigationIcon = if (stack.size > 1) androidx.appcompat.content.res.AppCompatResources
            .getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material) else null
        back.isEnabled = stack.size > 1
        val b = browser ?: return
        status.text = getString(R.string.loading)
        status.visibility = View.VISIBLE
        adapter.submit(emptyList())
        lifecycleScope.launch {
            val result = try {
                if (level.search != null) b.getSearchResult(level.search, 0, Int.MAX_VALUE, null).await()
                else b.getChildren(level.id, 0, Int.MAX_VALUE, null).await()
            } catch (e: Exception) {
                null
            }
            if (stack.lastOrNull() != level) return@launch
            val items = result?.value ?: emptyList()
            adapter.submit(items)
            status.text = getString(R.string.empty)
            status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun onItem(item: MediaItem) {
        val meta = item.mediaMetadata
        when {
            meta.isPlayable == true -> {
                val b = browser ?: return
                b.setMediaItem(item)
                b.prepare()
                b.play()
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            meta.isBrowsable == true && !item.mediaId.startsWith("info/") ->
                open(Level(item.mediaId, meta.title?.toString() ?: ""))
            item.mediaId == "info/login" -> startActivity(Intent(this, SettingsActivity::class.java))
            item.mediaId == "info/perm" -> requestPermissions()
        }
    }

    private fun updateNowPlaying() {
        val b = browser
        val item = b?.currentMediaItem
        nowPlaying.visibility = if (item != null) View.VISIBLE else View.GONE
        if (b == null || item == null) return
        nowPlayingTitle.text = listOfNotNull(item.mediaMetadata.title, item.mediaMetadata.subtitle).joinToString(" · ")
        nowPlayingToggle.setImageResource(if (b.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var items: List<MediaItem> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<MediaItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val prevGroup = items.getOrNull(position - 1)?.mediaMetadata?.extras
                ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)
            holder.bind(item, prevGroup)
        }
    }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val art: ImageView = view.findViewById(R.id.art)
        private val group: TextView = view.findViewById(R.id.group)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val progress: LinearProgressIndicator = view.findViewById(R.id.progress)

        fun bind(item: MediaItem, prevGroup: String?) {
            val meta = item.mediaMetadata
            val ex = meta.extras
            val g = ex?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)
            group.text = g
            group.visibility = if (g != null && g != prevGroup) View.VISIBLE else View.GONE
            title.text = meta.title
            subtitle.text = meta.subtitle
            subtitle.visibility = if (meta.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            val status = ex?.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, -1) ?: -1
            val pct = when (status) {
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED -> 1.0
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED ->
                    ex?.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE) ?: 0.0
                else -> 0.0
            }
            progress.visibility = if (pct > 0) View.VISIBLE else View.GONE
            progress.progress = (pct * 1000).toInt()
            val uri = meta.artworkUri
            val isIcon = uri == null || uri.scheme == "android.resource"
            // Ikony menu na farebnej dlazdici, plagaty a obaly cez celu plochu.
            val size = (48 * itemView.resources.displayMetrics.density).toInt()
            art.layoutParams = art.layoutParams.apply { height = if (isIcon) size else size * 4 / 3 }
            art.setBackgroundResource(if (isIcon) R.drawable.bg_icon_tile else R.drawable.bg_poster)
            art.clipToOutline = true
            art.scaleType = if (isIcon) ImageView.ScaleType.CENTER else ImageView.ScaleType.CENTER_CROP
            art.imageTintList = if (isIcon) android.content.res.ColorStateList.valueOf(0xFFE5A00D.toInt()) else null
            val placeholder = if (meta.isPlayable == true) R.drawable.ic_play else R.drawable.ic_folder
            ImageLoader.load(lifecycleScope, art, uri, placeholder)
            itemView.setOnClickListener { onItem(item) }
        }
    }
}
