package sk.talasek.mediaauto.ui

import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import sk.talasek.mediaauto.PlaybackService
import sk.talasek.mediaauto.R

/** Prehravanie s obrazom v telefone. Kym je otvorene, zapne v prehravaci video stopu. */
class PlayerActivity : AppCompatActivity() {

    private var controller: MediaController? = null
    private lateinit var view: PlayerView

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val msg = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.lastOrNull() ?: error.errorCodeName
            Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            title = mediaMetadata.title
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player_view)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        lifecycleScope.launch {
            val c = MediaController.Builder(this@PlayerActivity, token).buildAsync().await()
            controller = c
            c.addListener(listener)
            setVideo(c, true)
            view.player = c
        }
    }

    override fun onStop() {
        controller?.let {
            // Po odchode z obrazovky hra dalej len zvuk (napr. v aute alebo so zhasnutou obrazovkou).
            setVideo(it, false)
            it.release()
        }
        controller = null
        view.player = null
        super.onStop()
    }

    private fun setVideo(c: MediaController, enabled: Boolean) = PlaybackService.claimVideo(c, "phone", enabled)
}
