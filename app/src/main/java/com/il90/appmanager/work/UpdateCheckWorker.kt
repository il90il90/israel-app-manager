package com.il90.appmanager.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.il90.appmanager.App
import com.il90.appmanager.data.Compatibility
import com.il90.appmanager.data.isNewerThan
import com.il90.appmanager.data.pickVariant
import java.util.concurrent.TimeUnit

/** Looks for new versions in the background and tells the user once per set of updates. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = App.from(applicationContext)
        val settings = app.settings.current()
        if (!settings.autoCheck) return Result.success()
        if (!app.catalog.refresh()) return Result.retry()
        val catalog = app.catalog.state.value.catalog ?: return Result.success()

        val candidates = catalog.apps.mapNotNull { entry ->
            (pickVariant(entry, app.device) as? Compatibility.Ok)?.let { entry to it.variant }
        }
        val installed = app.installed.snapshot(candidates.map { it.second.packageName }.toSet())
        val updates = candidates.filter { (_, variant) ->
            val have = installed[variant.packageName] ?: return@filter false
            variant.isNewerThan(have)
        }
        val signature = updates.joinToString(",") { (_, v) -> "${v.packageName}:${if (v.live) v.versionName else v.versionCode.toString()}" }
        if (updates.isEmpty()) {
            app.notifier.cancelUpdates()
            if (settings.notifiedUpdates.isNotEmpty()) app.settings.setNotifiedUpdates("")
        } else if (signature != settings.notifiedUpdates) {
            app.notifier.postUpdates(updates.map { it.first.name })
            app.settings.setNotifiedUpdates(signature)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "update-check"

        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
