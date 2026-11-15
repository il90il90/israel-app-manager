package com.il90.appmanager.install

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.il90.appmanager.App
import com.il90.appmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while downloads and installs run, so leaving the app does not
 * stall them. It only mirrors [InstallManager]'s state; the work itself lives there.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = App.from(this)
        val started = runCatching {
            ServiceCompat.startForeground(
                this, Notifier.ID_PROGRESS,
                app.notifier.progress(getString(R.string.notif_working), null, 1),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                app.installManager.tasks.collectLatest { tasks ->
                    val working = tasks.values.filter { it.state.isWorking }
                    if (working.isEmpty()) {
                        // Give a task that is about to start a moment before giving up the service.
                        delay(1200)
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    val first = working.firstOrNull { it.state is TaskState.Downloading } ?: working.first()
                    val (title, fraction) = when (val state = first.state) {
                        is TaskState.Downloading -> getString(R.string.notif_downloading, first.name) to state.fraction
                        TaskState.Installing, TaskState.Verifying -> getString(R.string.notif_installing, first.name) to null
                        else -> getString(R.string.notif_downloading, first.name) to null
                    }
                    runCatching {
                        ServiceCompat.startForeground(
                            this@DownloadService, Notifier.ID_PROGRESS,
                            app.notifier.progress(title, fraction, working.size),
                            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                        )
                    }
                    delay(350) // at most ~3 notification updates a second
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            // Refused when the app is not in front; the work then simply runs without the service.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
