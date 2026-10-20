/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.domain.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.infinity.mpvz.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps yt-dlp subprocess downloads alive while the app is
 * backgrounded. Runs the engine queue sequentially and mirrors progress into its
 * foreground notification; stops itself when the queue drains.
 */
class YtdlpDownloadService : Service() {
  private val engine: YtdlpDownloadEngine by inject()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var drainJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    if (intent?.action == ACTION_CANCEL_ACTIVE) {
      engine.jobs.value.firstOrNull { it.state == YtdlpDownloadEngine.JobState.RUNNING }?.let { engine.cancel(it.id) }
    }
    ensureChannel()
    startAsForeground(buildNotification(null))
    if (drainJob?.isActive != true) {
      drainJob =
        scope.launch {
          engine.drainQueue { job -> notify(buildNotification(job)) }
          stopSelf()
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun startAsForeground(notification: android.app.Notification) {
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      }
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
  }

  private fun notify(notification: android.app.Notification) {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, notification)
  }

  private fun buildNotification(job: YtdlpDownloadEngine.Job?): android.app.Notification {
    val queued = engine.jobs.value.count { it.state == YtdlpDownloadEngine.JobState.QUEUED }
    val title = job?.title ?: getString(R.string.downloads_notification_channel)
    val running = job?.state == YtdlpDownloadEngine.JobState.RUNNING
    val progress = job?.progressPercent?.toInt() ?: 0
    val text =
      when {
        running && job != null -> {
          val queueSuffix = if (queued > 0) " (+$queued)" else ""
          "$progress% ${job.detail}$queueSuffix".trim()
        }
        else -> getString(R.string.downloads_preparing)
      }

    val cancelIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, YtdlpDownloadService::class.java).setAction(ACTION_CANCEL_ACTIVE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val contentIntent =
      PendingIntent.getActivity(
        this,
        4,
        Intent(this, app.infinity.mpvz.MainActivity::class.java)
          .putExtra(app.infinity.mpvz.MainActivity.EXTRA_OPEN_DOWNLOADS, true)
          .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat
      .Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(contentIntent)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setProgress(100, progress, !running || progress <= 0)
      .addAction(0, getString(android.R.string.cancel), cancelIntent)
      .build()
  }

  private fun ensureChannel() {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.downloads_notification_channel),
          NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.downloads_notification_channel_description) },
      )
    }
  }

  companion object {
    private const val CHANNEL_ID = "ytdlp_downloads"
    private const val NOTIFICATION_ID = 0x59D1
    private const val ACTION_CANCEL_ACTIVE = "app.infinity.mpvz.download.CANCEL_ACTIVE"

    fun start(context: Context) {
      val intent = Intent(context, YtdlpDownloadService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
