package com.il90.appmanager.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.il90.appmanager.BuildConfig
import com.il90.appmanager.R
import com.il90.appmanager.data.ThemeMode
import com.il90.appmanager.data.UserSettings
import com.il90.appmanager.ui.AppRow
import com.il90.appmanager.ui.components.TelegramButton
import com.il90.appmanager.ui.components.ActionButton
import com.il90.appmanager.ui.components.AppIcon
import com.il90.appmanager.util.formatSize
import com.il90.appmanager.util.ltr
import com.il90.appmanager.util.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    cacheSize: Long,
    selfUpdate: AppRow?,
    refreshing: Boolean,
    fetchedAt: Long,
    onBack: () -> Unit,
    onAutoCheck: (Boolean) -> Unit,
    onDeleteAfterInstall: (Boolean) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onClearCache: () -> Unit,
    onCheckNow: () -> Unit,
    onSelfUpdate: () -> Unit,
    onOpenSource: () -> Unit,
    onShareApp: () -> Unit,
    telegram: String?,
    onOpenUrl: (String) -> Unit,
    snackbar: SnackbarHostState,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Group(stringResource(R.string.settings_updates)) {
                    SwitchRow(stringResource(R.string.settings_auto_check), stringResource(R.string.settings_auto_check_desc), settings.autoCheck, onAutoCheck)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_list_title), style = MaterialTheme.typography.bodyLarge)
                            if (fetchedAt > 0) {
                                Text(
                                    stringResource(R.string.settings_list_updated, relativeTime(LocalContext.current, fetchedAt)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        OutlinedButton(onClick = onCheckNow, enabled = !refreshing) { Text(stringResource(R.string.settings_check_now)) }
                    }
                }
                Group(stringResource(R.string.settings_downloads)) {
                    SwitchRow(stringResource(R.string.settings_delete_after), stringResource(R.string.settings_delete_after_desc), settings.deleteAfterInstall, onDeleteAfterInstall)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_clear_cache), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_cache_size, formatSize(cacheSize).ifEmpty { "0 KB" }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onClearCache, enabled = cacheSize > 0) { Text(stringResource(R.string.action_clear)) }
                    }
                }
                Group(stringResource(R.string.settings_appearance)) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(10.dp))
                        val options = listOf(
                            ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                            ThemeMode.LIGHT to stringResource(R.string.theme_light),
                            ThemeMode.DARK to stringResource(R.string.theme_dark),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { index, (mode, label) ->
                                SegmentedButton(
                                    selected = settings.theme == mode,
                                    onClick = { onTheme(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                    }
                }
                Group(stringResource(R.string.settings_about)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        OwnIcon(48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.settings_version, ltr(BuildConfig.VERSION_NAME)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (selfUpdate != null) stringResource(R.string.settings_app_update, ltr(selfUpdate.variant?.versionName.orEmpty()))
                                else stringResource(R.string.settings_app_up_to_date),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selfUpdate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (selfUpdate != null) {
                            if (selfUpdate.task != null) ActionButton(selfUpdate, onSelfUpdate)
                            else Button(onClick = onSelfUpdate) { Text(stringResource(R.string.action_update)) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onShareApp).padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.action_share_app), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onOpenSource).padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_source), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (telegram != null) {
                    TelegramButton(telegram, stringResource(R.string.settings_contact_telegram), onOpenUrl)
                }
                Text(
                    stringResource(R.string.settings_made_by),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** This app's own launcher icon, as the launcher draws it. */
@Composable
private fun OwnIcon(size: Dp) {
    val context = LocalContext.current
    val bitmap = remember {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, null, Modifier.size(size)) else AppIcon(null, "I", null, size)
}
