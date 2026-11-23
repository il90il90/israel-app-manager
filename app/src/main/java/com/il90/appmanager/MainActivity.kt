package com.il90.appmanager

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.il90.appmanager.data.FormFactor
import com.il90.appmanager.data.ThemeMode
import com.il90.appmanager.data.Variant
import com.il90.appmanager.install.InstallEvent
import com.il90.appmanager.install.InstallManager
import com.il90.appmanager.ui.AppRow
import com.il90.appmanager.ui.AppViewModel
import com.il90.appmanager.ui.Filter
import com.il90.appmanager.ui.PrimaryAction
import com.il90.appmanager.ui.components.LocalDevice
import com.il90.appmanager.ui.components.ShareSheet
import com.il90.appmanager.ui.details.DetailsScreen
import com.il90.appmanager.ui.home.HomeScreen
import com.il90.appmanager.ui.primaryAction
import com.il90.appmanager.ui.shareLink
import com.il90.appmanager.ui.shareableVariant
import com.il90.appmanager.ui.settings.SettingsScreen
import com.il90.appmanager.ui.theme.IamTheme
import com.il90.appmanager.ui.theme.LocalDarkTheme
import com.il90.appmanager.util.dotJoin
import com.il90.appmanager.util.formatSize
import com.il90.appmanager.util.ltr
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    private val requestedFilter = MutableStateFlow<Filter?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { false }
        readIntent(intent)

        // Android's own install confirmation can only be put on screen by a visible activity.
        val installs = App.from(this).installManager
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                installs.confirm.filterNotNull().collect { request ->
                    installs.confirmShown(request)
                    runCatching { startActivity(request.intent) }
                }
            }
        }

        val device = App.from(this).device
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalDevice provides device) {
                IamTheme(settings.theme) {
                    SystemBars(settings.theme)
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        // A TV may cut off the edges of the picture (overscan): keep everything
                        // inside the area Android TV's design guidelines call safe.
                        val safe = if (device.formFactor == FormFactor.TV) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier
                        Box(safe) { AppContent(viewModel, requestedFilter) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun readIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_FILTER) == FILTER_UPDATES) requestedFilter.value = Filter.UPDATES
    }

    @Composable
    private fun SystemBars(mode: ThemeMode) {
        val dark = LocalDarkTheme.current
        DisposableEffect(dark, mode) {
            val transparent = Color.Transparent.toArgb()
            enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
            )
            onDispose { }
        }
    }

    companion object {
        const val EXTRA_FILTER = "filter"
        const val FILTER_UPDATES = "updates"
    }
}

private const val SHARE_SELF = "@self"

/** Always the newest release of this app: the release workflow keeps this asset name. */
private const val SELF_LATEST_APK = "https://github.com/${BuildConfig.CATALOG_REPO}/releases/latest/download/IsraelAppManager.apk"

@Composable
private fun AppContent(viewModel: AppViewModel, requestedFilter: MutableStateFlow<Filter?>) {
    val activity = LocalActivity.current as ComponentActivity
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheSize by viewModel.cacheSize.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }

    val wanted by requestedFilter.collectAsStateWithLifecycle()
    LaunchedEffect(wanted) {
        wanted?.let {
            filter = it
            navController.popBackStack("home", inclusive = false)
            requestedFilter.value = null
        }
    }

    // ---- "install unknown apps" gate ---------------------------------------------------
    var pending by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var askPermission by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun start(rows: List<AppRow>) {
        viewModel.installAll(rows)
        if (Build.VERSION.SDK_INT >= 33 && !settings.notificationsAsked && !InstallManager.hasNotificationPermission(activity)) {
            viewModel.markNotificationsAsked()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val unknownSources = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (InstallManager.canInstall(activity)) start(pending)
        pending = emptyList()
    }
    fun install(rows: List<AppRow>) {
        val wantedRows = rows.filter { it.variant != null && !it.isBusy }
        if (wantedRows.isEmpty()) return
        if (InstallManager.canInstall(activity)) {
            start(wantedRows)
        } else {
            pending = wantedRows
            askPermission = true
        }
    }
    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false; pending = emptyList() },
            title = { Text(stringResource(R.string.permission_install_title)) },
            text = { Text(stringResource(R.string.permission_install_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askPermission = false
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())
                    runCatching { unknownSources.launch(intent) }.onFailure { start(pending); pending = emptyList() }
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { askPermission = false; pending = emptyList() }) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }

    // ---- actions --------------------------------------------------------------------------
    val cannotOpen = stringResource(R.string.msg_cannot_open)
    fun openApp(row: AppRow) {
        val pkg = row.variant?.packageName ?: return
        val pm = activity.packageManager
        // On a TV an app's TV screen comes first; its phone screen is the fallback (and the
        // other way round everywhere else).
        val tv = App.from(activity).device.formFactor == FormFactor.TV
        val launch = if (tv) pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        else pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg)
        if (launch != null) runCatching { activity.startActivity(launch) }
        else runCatching {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri()))
        }.onFailure { activity.lifecycleScope.launch { snackbar.showSnackbar(cannotOpen) } }
    }
    fun primary(row: AppRow) {
        when (row.primaryAction()) {
            PrimaryAction.INSTALL, PrimaryAction.UPDATE -> install(listOf(row))
            PrimaryAction.RETRY -> { viewModel.cancel(row); install(listOf(row.copy(task = null))) }
            PrimaryAction.CANCEL -> viewModel.cancel(row)
            PrimaryAction.OPEN -> openApp(row)
            PrimaryAction.NONE -> Unit
        }
    }
    fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
        }
    }
    fun uninstall(row: AppRow) {
        val pkg = row.variant?.packageName ?: return
        runCatching { activity.startActivity(Intent(Intent.ACTION_DELETE, "package:$pkg".toUri())) }
    }
    fun appInfo(row: AppRow) {
        val pkg = row.variant?.packageName ?: return
        runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri())) }
    }

    // ---- sharing ---------------------------------------------------------------------------
    // What is being shared: an app's id, or SHARE_SELF for this app.
    var sharing by rememberSaveable { mutableStateOf<String?>(null) }
    var shareProgress by remember { mutableStateOf<Float?>(null) }
    var shareJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val shareLinkText = stringResource(R.string.share_link_text)
    val linkCopied = stringResource(R.string.msg_link_copied)
    val shareFailed = stringResource(R.string.msg_share_apk_failed)
    fun closeShare() {
        shareJob?.cancel()
        shareJob = null
        shareProgress = null
        sharing = null
    }
    fun sendLink(name: String, link: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareLinkText.format(name, link))
        runCatching { activity.startActivity(Intent.createChooser(send, null)) }
    }
    fun copyLink(name: String, link: String) {
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(name, link))
        // Android 13 and up show their own confirmation.
        if (Build.VERSION.SDK_INT < 33) activity.lifecycleScope.launch { snackbar.showSnackbar(linkCopied) }
    }
    fun sendFile(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        runCatching { activity.startActivity(Intent.createChooser(send, null)) }
    }
    sharing?.let { id ->
        val own = id == SHARE_SELF
        val row = if (own) state.self else state.rows.firstOrNull { it.id == id }
        val name = if (own) stringResource(R.string.app_name) else row?.app?.name
        if (name == null) {
            // The catalog changed under the sheet.
            LaunchedEffect(id) { closeShare() }
        } else {
            val link = if (own) SELF_LATEST_APK else row?.shareLink()
            val variant = if (own) null else row?.shareableVariant()
            val ownSize = remember { File(activity.applicationInfo.sourceDir).length() }
            val apkNote = when {
                own -> dotJoin(ltr(BuildConfig.VERSION_NAME), formatSize(ownSize))
                variant != null -> dotJoin(ltr(variant.versionName), formatSize(variant.size))
                else -> null
            }
            ShareSheet(
                name = name,
                link = link,
                apkNote = apkNote,
                progress = shareProgress,
                onSendLink = { link?.let { sendLink(name, it) }; closeShare() },
                onCopyLink = { link?.let { copyLink(name, it) }; closeShare() },
                onSendApk = {
                    shareJob = scope.launch {
                        shareProgress = -1f
                        val file = when {
                            own -> viewModel.ownApk()
                            row != null && variant != null -> viewModel.prepareApk(row, variant) { shareProgress = it }
                            else -> null
                        }
                        shareJob = null
                        closeShare()
                        if (file != null) sendFile(file) else snackbar.showSnackbar(shareFailed)
                    }
                },
                onDismiss = ::closeShare,
            )
        }
    }

    val savingApk = stringResource(R.string.msg_apk_download_started)
    val showDownloads = stringResource(R.string.action_show)
    fun downloadVariant(row: AppRow, variant: Variant) {
        val manager = activity.getSystemService(DownloadManager::class.java) ?: return
        val name = variant.fileName.ifEmpty { "${row.id}-${variant.versionName}.apk" }
        val request = DownloadManager.Request(variant.url.toUri())
            .setTitle(name)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        runCatching { manager.enqueue(request) }.onSuccess {
            activity.lifecycleScope.launch {
                val result = snackbar.showSnackbar(savingApk.format(name), actionLabel = showDownloads, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) {
                    runCatching { activity.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                }
            }
        }
    }

    val installedMessage = stringResource(R.string.msg_installed)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is InstallEvent.Installed) snackbar.showSnackbar(installedMessage.format(event.name))
        }
    }
    val cleared = stringResource(R.string.msg_cache_cleared)

    NavHost(
        navController = navController,
        startDestination = "home",
        enterTransition = { slideInHorizontally(initialOffsetX = { it / 6 }) + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { it / 6 }) + fadeOut() },
    ) {
        composable("home") {
            HomeScreen(
                state = state,
                filter = filter,
                onFilter = { filter = it },
                onOpen = { navController.navigate("details/${it.id}") { launchSingleTop = true } },
                onAction = ::primary,
                onUpdateAll = { install(state.updates) },
                onRefresh = viewModel::refresh,
                onSettings = { viewModel.refreshCacheSize(); navController.navigate("settings") { launchSingleTop = true } },
                onShareApp = { sharing = SHARE_SELF },
                onDismissAnnouncement = { viewModel.dismissAnnouncement(it) },
                onOpenUrl = ::openUrl,
                onDownload = ::downloadVariant,
                snackbar = snackbar,
            )
        }
        composable("details/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val row = state.rows.firstOrNull { it.id == id } ?: state.selfUpdate?.takeIf { it.id == id }
            if (row == null) {
                // The catalog changed under us (or is still loading after a restore).
                LaunchedEffect(state.loading) { if (!state.loading) navController.popBackStack() }
            } else {
                DetailsScreen(
                    row = row,
                    onBack = { navController.popBackStack() },
                    onAction = { primary(row) },
                    onOpenApp = { openApp(row) },
                    onUninstall = { uninstall(row) },
                    onAppInfo = { appInfo(row) },
                    onShare = { sharing = row.id },
                    onOpenUrl = ::openUrl,
                    onDownloadVariant = { downloadVariant(row, it) },
                    snackbar = snackbar,
                )
            }
        }
        composable("settings") {
            SettingsScreen(
                settings = settings,
                cacheSize = cacheSize,
                selfUpdate = state.selfUpdate,
                refreshing = state.refreshing,
                fetchedAt = state.fetchedAt,
                onBack = { navController.popBackStack() },
                onAutoCheck = { viewModel.setAutoCheck(it) },
                onDeleteAfterInstall = { viewModel.setDeleteAfterInstall(it) },
                onTheme = { viewModel.setTheme(it) },
                onClearCache = {
                    viewModel.clearCache()
                    activity.lifecycleScope.launch { snackbar.showSnackbar(cleared) }
                },
                onCheckNow = viewModel::refresh,
                onSelfUpdate = { state.selfUpdate?.let(::primary) },
                onOpenSource = { openUrl("https://github.com/${BuildConfig.CATALOG_REPO}") },
                onShareApp = { sharing = SHARE_SELF },
                telegram = state.telegram,
                onOpenUrl = ::openUrl,
                snackbar = snackbar,
            )
        }
    }
}
