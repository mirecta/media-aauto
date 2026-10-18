package sk.talasek.mediaauto.download

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import sk.talasek.mediaauto.App
import sk.talasek.mediaauto.R

/** Stahovanie na pozadi s notifikaciou o priebehu. */
@OptIn(UnstableApi::class)
class MediaDownloadService : DownloadService(
    1002, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, "downloads", R.string.downloads, 0
) {
    private val helper by lazy { DownloadNotificationHelper(this, "downloads") }

    override fun getDownloadManager(): DownloadManager = App.get(this).downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, R.drawable.ic_download, null, null, downloads, notMetRequirements)
}
