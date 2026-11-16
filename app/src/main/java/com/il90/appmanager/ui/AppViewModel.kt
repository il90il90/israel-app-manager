package com.il90.appmanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.il90.appmanager.App
import com.il90.appmanager.BuildConfig
import com.il90.appmanager.data.Announcement
import com.il90.appmanager.data.CatalogApp
import com.il90.appmanager.data.Compatibility
import com.il90.appmanager.data.FormFactor
import com.il90.appmanager.data.InstalledInfo
import com.il90.appmanager.data.ThemeMode
import com.il90.appmanager.data.UserSettings
import com.il90.appmanager.data.Variant
import com.il90.appmanager.data.iconUrl
import com.il90.appmanager.data.pickVariant
import com.il90.appmanager.install.InstallEvent
import com.il90.appmanager.install.Task
import com.il90.appmanager.install.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppStatus { NOT_INSTALLED, UP_TO_DATE, UPDATE_AVAILABLE, UNAVAILABLE }

enum class Filter { ALL, INSTALLED, UPDATES }

/** One app as the UI shows it: catalog entry + this device + what is installed + what is running. */
data class AppRow(
    val app: CatalogApp,
    val compatibility: Compatibility,
    val variant: Variant?,
    val otherVariants: List<Variant>,
    val installed: InstalledInfo?,
    val status: AppStatus,
    val task: Task?,
    val iconUrl: String?,
) {
    val id: String get() = app.id
    val isBusy: Boolean get() = task != null && task.state.isActive
}

data class HomeState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val fetchedAt: Long = 0,
    val rows: List<AppRow> = emptyList(),
    /** This app's own catalog entry. */
    val self: AppRow? = null,
    /** This app's own catalog entry, when a newer version of it can be installed. */
    val selfUpdate: AppRow? = null,
    val announcement: Announcement? = null,
    /** The developer's Telegram link, for the contact row in Settings. */
    val telegram: String? = null,
) {
    val updates: List<AppRow> get() = rows.filter { it.status == AppStatus.UPDATE_AVAILABLE }
    val installedCount: Int get() = rows.count { it.installed != null }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    val settings: StateFlow<UserSettings> =
        app.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val events = app.installManager.events

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize

    val state: StateFlow<HomeState> = combine(
        app.catalog.state, app.installed.state, app.installManager.tasks, app.settings.flow,
    ) { catalogState, installed, tasks, settings ->
        val catalog = catalogState.catalog
        if (catalog == null) {
            return@combine HomeState(loading = catalogState.loading, refreshing = catalogState.refreshing, offline = catalogState.offline)
        }
        app.installed.watch(catalog.apps.flatMap { entry -> entry.variants.map { it.packageName } }.toSet())
        val ownPackage = app.packageName
        var self: AppRow? = null
        var selfUpdate: AppRow? = null
        val rows = ArrayList<AppRow>()
        for (entry in catalog.apps) {
            val compatibility = pickVariant(entry, app.device)
            val variant = (compatibility as? Compatibility.Ok)?.variant
            val isSelf = entry.self || entry.variants.any { it.packageName == ownPackage }
            val have = when {
                variant == null -> null
                isSelf && variant.packageName == ownPackage -> InstalledInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), null)
                else -> installed[variant.packageName]
            }
            val status = when {
                variant == null -> AppStatus.UNAVAILABLE
                have == null -> AppStatus.NOT_INSTALLED
                variant.versionCode > have.versionCode -> AppStatus.UPDATE_AVAILABLE
                else -> AppStatus.UP_TO_DATE
            }
            val row = AppRow(
                app = entry,
                compatibility = compatibility,
                variant = variant,
                otherVariants = entry.variants.filter { it.formFactor != app.device.formFactor }
                    .distinctBy { it.formFactor to it.abis },
                installed = have,
                status = status,
                task = tasks[entry.id],
                iconUrl = entry.iconUrl(catalog.baseUrl),
            )
            if (isSelf) {
                self = row
                // Only a build that carries the published package name can be updated in place.
                if (variant?.packageName == ownPackage && (status == AppStatus.UPDATE_AVAILABLE || row.task != null)) selfUpdate = row
            } else {
                rows += row
            }
        }
        HomeState(
            loading = false,
            refreshing = catalogState.refreshing,
            offline = catalogState.offline,
            fetchedAt = catalogState.fetchedAt,
            rows = rows,
            self = self,
            selfUpdate = selfUpdate,
            announcement = catalog.announcement?.takeIf { it.id.isNotEmpty() && it.id != settings.dismissedAnnouncement },
            telegram = catalog.contact?.telegram,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    init {
        viewModelScope.launch { app.catalog.load() }
        viewModelScope.launch {
            app.installManager.events.collect { event ->
                if (event is InstallEvent.Installed) {
                    app.installed.refresh()
                    refreshCacheSize()
                }
            }
        }
        refreshCacheSize()
    }

    fun row(id: String): AppRow? = state.value.rows.firstOrNull { it.id == id } ?: state.value.selfUpdate?.takeIf { it.id == id }

    fun refresh() {
        viewModelScope.launch {
            app.catalog.refresh()
            app.installed.refresh()
        }
    }

    fun onResume() {
        app.installed.refresh()
        // Coming back after a while: make sure the list is not stale.
        val age = System.currentTimeMillis() - state.value.fetchedAt
        if (!state.value.refreshing && age > 10 * 60 * 1000) refresh()
    }

    fun install(row: AppRow) {
        val variant = row.variant ?: return
        app.installManager.enqueue(row.app, variant)
        app.notifier.cancelUpdates()
    }

    fun installAll(rows: List<AppRow>) = rows.forEach { if (!it.isBusy) install(it) }

    fun cancel(row: AppRow) = app.installManager.cancel(row.id)

    fun setAutoCheck(value: Boolean) = viewModelScope.launch { app.settings.setAutoCheck(value) }
    fun setDeleteAfterInstall(value: Boolean) = viewModelScope.launch { app.settings.setDeleteAfterInstall(value) }
    fun setTheme(value: ThemeMode) = viewModelScope.launch { app.settings.setTheme(value) }
    fun dismissAnnouncement(id: String) = viewModelScope.launch { app.settings.dismissAnnouncement(id) }
    fun markNotificationsAsked() = viewModelScope.launch { app.settings.setNotificationsAsked() }

    suspend fun prepareApk(row: AppRow, variant: Variant, onProgress: (Float) -> Unit) =
        app.installManager.prepareForSharing(row.app, variant, onProgress).also { refreshCacheSize() }

    suspend fun ownApk() = app.installManager.ownApk("IsraelAppManager-${BuildConfig.VERSION_NAME}.apk")

    fun refreshCacheSize() {
        viewModelScope.launch { _cacheSize.value = app.installManager.cacheSize() }
    }

    fun clearCache() {
        viewModelScope.launch {
            app.installManager.clearCache()
            _cacheSize.value = app.installManager.cacheSize()
        }
    }
}

/** What the main button of a row does right now. */
enum class PrimaryAction { INSTALL, UPDATE, OPEN, CANCEL, RETRY, NONE }

fun AppRow.primaryAction(): PrimaryAction {
    val state = task?.state
    return when {
        state is TaskState.Failed -> PrimaryAction.RETRY
        state is TaskState.Queued || state is TaskState.Downloading -> PrimaryAction.CANCEL
        state != null -> PrimaryAction.NONE
        status == AppStatus.UPDATE_AVAILABLE -> PrimaryAction.UPDATE
        status == AppStatus.NOT_INSTALLED -> PrimaryAction.INSTALL
        status == AppStatus.UP_TO_DATE -> PrimaryAction.OPEN
        else -> PrimaryAction.NONE
    }
}

/**
 * The APK to send to someone else: their device is unknown, so prefer the phone build that
 * runs on the most processors.
 */
fun CatalogApp.shareableVariant(): Variant? {
    val phone = variants.filter { it.formFactor == FormFactor.PHONE }
    return phone.ifEmpty { variants }.maxWithOrNull(
        compareBy<Variant>({ if (it.abis.isEmpty()) Int.MAX_VALUE else it.abis.size }, { if ("arm64-v8a" in it.abis) 1 else 0 }, { -it.size })
    )
}

/** The best link to give someone for this app: always-latest when the catalog has one. */
fun CatalogApp.shareLink(): String? = latestUrl ?: release?.url ?: storeUrl ?: website ?: repoUrl

fun AppRow.shareableVariant(): Variant? = app.shareableVariant()

fun AppRow.shareLink(): String? = app.shareLink()
