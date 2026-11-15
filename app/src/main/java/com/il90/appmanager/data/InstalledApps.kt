package com.il90.appmanager.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InstalledInfo(
    val versionName: String,
    val versionCode: Long,
    val installer: String?,
)

/** Tracks which catalog packages are installed, and keeps up with installs and removals. */
class InstalledApps(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<Map<String, InstalledInfo>>(emptyMap())
    val state: StateFlow<Map<String, InstalledInfo>> = _state.asStateFlow()

    @Volatile
    private var watched: Set<String> = emptySet()

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                refresh()
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun watch(packages: Set<String>) {
        if (packages == watched) return
        watched = packages
        refresh()
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) { _state.value = snapshot(watched) }
    }

    fun snapshot(packages: Set<String>): Map<String, InstalledInfo> {
        val pm = context.packageManager
        val result = HashMap<String, InstalledInfo>()
        for (pkg in packages) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                result[pkg] = InstalledInfo(
                    versionName = info.versionName.orEmpty(),
                    versionCode = PackageInfoCompat.getLongVersionCode(info),
                    installer = installerOf(pm, pkg),
                )
            } catch (_: PackageManager.NameNotFoundException) {
                // not installed
            }
        }
        return result
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    } catch (_: Exception) {
        null
    }
}
