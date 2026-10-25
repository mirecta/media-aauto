package sk.talasek.mediaauto.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.MoreExecutors
import sk.talasek.mediaauto.PlaybackService
import sk.talasek.mediaauto.R

/**
 * Obraz prehravaneho videa na displeji auta, len ked auto stoji.
 *
 * Android Auto zatial nepovoluje video aplikacie tretich stran, preto sa aplikacia hlasi ako
 * navigacna: tie dostanu od auta plochu (Surface) na kreslenie mapy. Na nu vytvorime virtualny
 * displej s PlayerView napojenym na PlaybackService. Vyber obsahu ostava v media casti aplikacie.
 *
 * Obraz sa ukazuje len pri overenej nulovej rychlosti auta; inak (jazda alebo neznama rychlost)
 * je plocha zakryta a pokracuje len zvuk.
 */
class VideoCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = VideoSession()
}

private class VideoSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = VideoScreen(carContext)
}

private class VideoScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    companion object {
        private const val SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
        private const val PARKED_MPS = 0.5f
        private const val SEEK_MS = 30_000L
    }

    private var controller: MediaController? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var playerView: PlayerView? = null
    private var cover: TextView? = null

    /** null = rychlost nie je znama. */
    private var speed: Float? = null
    private val parked get() = speed?.let { it < PARKED_MPS } == true

    private val speedListener = OnCarDataAvailableListener<Speed> { data ->
        // Android Auto casto posiela len zobrazovanu rychlost (surova je "nepodporovana").
        val value = listOf(data.rawSpeedMetersPerSecond, data.displaySpeedMetersPerSecond)
            .firstOrNull { it.status == CarValue.STATUS_SUCCESS && it.value != null }?.value
        speed = value?.let { kotlin.math.abs(it) }
        updateCover()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateCover()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) invalidate()
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            val surface = container.surface ?: return
            releaseDisplay()
            val dm = ContextCompat.getSystemService(carContext, DisplayManager::class.java) ?: return
            val vd = dm.createVirtualDisplay("MediaAutoVideo", container.width, container.height, container.dpi, surface, 0)
            display = vd
            showPresentation(vd)
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) = releaseDisplay()

        override fun onClick(x: Float, y: Float) = togglePlay()
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(androidx.car.app.AppManager::class.java).setSurfaceCallback(surfaceCallback)
        val token = SessionToken(carContext, ComponentName(carContext, PlaybackService::class.java))
        val future = MediaController.Builder(carContext, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) {
                c.release()
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            setVideoTrack(c, true)
            playerView?.player = c
            updateCover()
            invalidate()
        }, ContextCompat.getMainExecutor(carContext))
        startSpeed()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(speedListener)
        }
        releaseDisplay()
        controller?.let {
            setVideoTrack(it, false)
            it.removeListener(playerListener)
            it.release()
        }
        controller = null
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startSpeed() {
        if (ContextCompat.checkSelfPermission(carContext, SPEED_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            carContext.requestPermissions(listOf(SPEED_PERMISSION)) { granted, _ ->
                if (SPEED_PERMISSION in granted) startSpeed() else updateCover()
            }
            return
        }
        runCatching {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(MoreExecutors.directExecutor(), speedListener)
        }.onFailure {
            updateCover()
        }
    }

    /** Kazda obrazovka v aute ma vlastne ID, aby zatvaranie starej nevyplo obraz novej. */
    private val videoId = "car-" + System.identityHashCode(this)

    /** Video stopu chceme, len kym je obrazovka v aute otvorena (inak sa zbytocne dekoduje). */
    private fun setVideoTrack(c: MediaController, enabled: Boolean) = PlaybackService.claimVideo(c, videoId, enabled)

    @SuppressLint("UnsafeOptInUsageError")
    private fun showPresentation(vd: VirtualDisplay) {
        val p = Presentation(carContext, vd.display)
        val root = FrameLayout(p.context).apply { setBackgroundColor(Color.BLACK) }
        val pv = PlayerView(p.context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            player = controller
        }
        val text = TextView(p.context).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(pv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        p.setContentView(root)
        p.show()
        presentation = p
        playerView = pv
        cover = text
        updateCover()
    }

    private fun releaseDisplay() {
        playerView?.player = null
        playerView = null
        cover = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
    }

    /** Zakryje obraz, ak auto ide (alebo rychlost nepozname) alebo sa nic neprehrava. */
    private fun updateCover() {
        val text = cover ?: return
        val c = controller
        val message = when {
            ContextCompat.checkSelfPermission(carContext, SPEED_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                "Povoľte prístup k rýchlosti auta\n\nOtvorte Médiá Auto v telefóne a povoľte „rýchlosť auta“"
            speed == null -> "Rýchlosť auta nie je známa – video sa zobrazí len keď auto stojí"
            !parked -> "Video je dostupné len keď auto stojí\n(zvuk pokračuje)"
            c == null || c.currentMediaItem == null -> "Nič sa neprehráva\n\nVyberte film alebo seriál v aplikácii Médiá Auto"
            c.currentMediaItem?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC -> "Prehráva sa hudba"
            else -> null
        }
        text.post {
            text.text = message
            text.visibility = if (message == null) View.GONE else View.VISIBLE
        }
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    override fun onGetTemplate(): Template {
        val c = controller
        val playing = c?.isPlaying == true
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_replay_30)).setOnClickListener {
                controller?.let { it.seekTo(maxOf(0, it.currentPosition - SEEK_MS)) }
            }.build())
            .addAction(Action.Builder().setIcon(icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play))
                .setOnClickListener { togglePlay() }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_forward_30)).setOnClickListener {
                controller?.let { it.seekTo(it.currentPosition + SEEK_MS) }
            }.build())
            .addAction(Action.Builder().setTitle("Koniec").setOnClickListener { carContext.finishCarApp() }.build())
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
