package com.il90.appmanager

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.il90.appmanager.data.CatalogRepository
import com.il90.appmanager.data.Device
import com.il90.appmanager.data.InstalledApps
import com.il90.appmanager.data.SettingsStore
import com.il90.appmanager.install.InstallManager
import com.il90.appmanager.install.Notifier
import com.il90.appmanager.work.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "IsraelAppManager/${BuildConfig.VERSION_NAME} (Android)")
                        .build()
                )
            }
            .build()
    }
    val device: Device by lazy { Device.of(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val notifier: Notifier by lazy { Notifier(this) }
    val catalog: CatalogRepository by lazy { CatalogRepository(this, http) }
    val installed: InstalledApps by lazy { InstalledApps(this, scope) }
    val installManager: InstallManager by lazy { InstallManager(this, http, settings, scope, notifier) }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            settings.flow.map { it.autoCheck }.distinctUntilChanged().collect { enabled ->
                UpdateCheckWorker.schedule(this@App, enabled)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .crossfade(true)
            .build()

    companion object {
        fun from(context: Context): App = context.applicationContext as App
    }
}
