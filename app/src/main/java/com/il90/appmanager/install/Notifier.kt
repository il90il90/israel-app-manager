package com.il90.appmanager.install

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.il90.appmanager.MainActivity
import com.il90.appmanager.R

/** Every notification the app posts. */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_INSTALL, context.getString(R.string.channel_install), NotificationManager.IMPORTANCE_HIGH),
            )
        )
    }

    private fun openApp(requestCode: Int, filter: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (filter != null) putExtra(MainActivity.EXTRA_FILTER, filter) }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setColor(context.getColor(R.color.brand))

    fun progress(title: String, fraction: Float?, count: Int): Notification = base(CHANNEL_DOWNLOADS)
        .setContentTitle(title)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
        .apply { if (count > 1) setSubText("+${count - 1}") }
        .setContentIntent(openApp(1))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    @SuppressLint("MissingPermission") // checked by hasNotificationPermission
    fun postUpdates(names: List<String>) {
        if (!InstallManager.hasNotificationPermission(context)) return
        val title = context.resources.getQuantityString(R.plurals.notif_updates_title, names.size, names.size)
        val text = names.joinToString(", ")
        val notification = base(CHANNEL_UPDATES)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(2, MainActivity.FILTER_UPDATES))
            .build()
        runCatching { manager.notify(ID_UPDATES, notification) }
    }

    fun cancelUpdates() = manager.cancel(ID_UPDATES)

    @SuppressLint("MissingPermission") // checked by hasNotificationPermission
    fun postConfirm(sessionId: Int, appName: String) {
        if (!InstallManager.hasNotificationPermission(context)) return
        val notification = base(CHANNEL_INSTALL)
            .setContentTitle(context.getString(R.string.notif_confirm_title, appName))
            .setContentText(context.getString(R.string.notif_confirm_body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(3))
            .build()
        runCatching { manager.notify(ID_CONFIRM_BASE + sessionId % 1000, notification) }
    }

    fun cancelConfirm(sessionId: Int) = manager.cancel(ID_CONFIRM_BASE + sessionId % 1000)

    companion object {
        const val CHANNEL_UPDATES = "updates"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_INSTALL = "install"
        const val ID_PROGRESS = 10
        const val ID_UPDATES = 20
        private const val ID_CONFIRM_BASE = 1000
    }
}
