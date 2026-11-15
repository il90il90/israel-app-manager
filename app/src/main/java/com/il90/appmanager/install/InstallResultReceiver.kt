package com.il90.appmanager.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.il90.appmanager.App

/** Receives what Android reports about an install session and hands it to [InstallManager]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0) return
        App.from(context).installManager.onInstallResult(
            sessionId = sessionId,
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
        )
    }

    companion object {
        const val ACTION = "com.il90.appmanager.INSTALL_RESULT"
    }
}
