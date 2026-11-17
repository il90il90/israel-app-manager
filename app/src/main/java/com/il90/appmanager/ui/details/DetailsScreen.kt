package com.il90.appmanager.ui.details

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.il90.appmanager.R
import com.il90.appmanager.data.Compatibility
import com.il90.appmanager.data.FormFactor
import com.il90.appmanager.data.License
import com.il90.appmanager.data.Variant
import com.il90.appmanager.data.androidVersionName
import com.il90.appmanager.data.isOlderThan
import com.il90.appmanager.data.localized
import com.il90.appmanager.install.TaskState
import com.il90.appmanager.ui.AppRow
import com.il90.appmanager.ui.AppStatus
import com.il90.appmanager.ui.PrimaryAction
import com.il90.appmanager.ui.components.AppIcon
import com.il90.appmanager.ui.components.MarkdownText
import com.il90.appmanager.ui.components.Pill
import com.il90.appmanager.ui.components.TelegramButton
import com.il90.appmanager.ui.components.categoryLabel
import com.il90.appmanager.ui.components.failureMessage
import com.il90.appmanager.ui.components.formFactorLabel
import com.il90.appmanager.ui.components.parseMarkdown
import com.il90.appmanager.ui.components.premiumColor
import com.il90.appmanager.ui.primaryAction
import com.il90.appmanager.ui.theme.LocalDarkTheme
import com.il90.appmanager.util.appLanguage
import com.il90.appmanager.util.dotJoin
import com.il90.appmanager.util.formatSize
import com.il90.appmanager.util.isolate
import com.il90.appmanager.util.ltr
import com.il90.appmanager.util.parseColor
import com.il90.appmanager.util.parseInstant
import com.il90.appmanager.util.readableOn
import com.il90.appmanager.util.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    row: AppRow,
    onBack: () -> Unit,
    onAction: () -> Unit,
    onOpenApp: () -> Unit,
    onUninstall: () -> Unit,
    onAppInfo: () -> Unit,
    onShare: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDownloadVariant: (Variant) -> Unit,
    snackbar: SnackbarHostState,
) {
    val language = appLanguage()
    val dark = LocalDarkTheme.current
    val accent = (parseColor(row.app.accent) ?: MaterialTheme.colorScheme.primary)
    var menu by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val collapsed by remember { derivedStateOf { scroll.value > 420 } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (collapsed) Text(row.app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (collapsed) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
                ),
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onShare) { Icon(Icons.Rounded.Share, stringResource(R.string.action_share)) }
                    if (row.installed != null) {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(stringResource(R.string.action_app_info)) }, { menu = false; onAppInfo() })
                            DropdownMenuItem({ Text(stringResource(R.string.action_uninstall)) }, { menu = false; onUninstall() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        // The content deliberately runs under the transparent top bar.
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            // A soft wash of the app's own colour behind the header.
            Box(
                Modifier.fillMaxWidth().height(360.dp).background(
                    Brush.verticalGradient(listOf(accent.copy(alpha = if (dark) 0.30f else 0.22f), Color.Transparent))
                )
            )
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
                val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = bottom),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Header(row, language)
                    ActionArea(row, accent.readableOn(dark), onAction, onOpenApp, onUninstall, onDownloadVariant, onOpenUrl)
                    row.app.license?.takeIf { it.required }?.let { LicenseCard(it, onOpenUrl) }
                    Notices(row)
                    QuickLinks(row, onOpenUrl)
                    Stats(row)
                    val description = row.app.description.localized(language)
                    if (description.isNotEmpty()) {
                        Section(stringResource(R.string.section_about)) {
                            Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    WhatsNew(row)
                    OtherDevices(row, onDownloadVariant)
                    DetailsTable(row, onOpenUrl)
                }
            }
        }
    }
}

@Composable
private fun Header(row: AppRow, language: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(row.iconUrl, row.app.name, row.app.accent, 92.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val by = dotJoin(
                row.app.developer.takeIf { it.isNotBlank() }?.let { stringResource(R.string.detail_by, isolate(it)) },
                categoryLabel(row.app.category),
            )
            Text(by, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val tagline = row.app.tagline.localized(language)
    if (tagline.isNotEmpty()) {
        Text(tagline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ActionArea(
    row: AppRow,
    accent: Color,
    onAction: () -> Unit,
    onOpenApp: () -> Unit,
    onUninstall: () -> Unit,
    onDownloadVariant: (Variant) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val state = row.task?.state
    val big = Modifier.height(52.dp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            state is TaskState.Failed -> {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(failureMessage(state.reason), Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onAction, big.weight(1f)) { Text(stringResource(R.string.action_retry)) }
                    if (row.installed != null) OutlinedButton(onUninstall, big.weight(1f)) { Text(stringResource(R.string.action_uninstall)) }
                }
            }
            state != null -> {
                val downloading = state as? TaskState.Downloading
                val fraction by animateFloatAsState(downloading?.fraction ?: 0f, label = "download")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val label = when (state) {
                            is TaskState.Downloading -> stringResource(R.string.status_downloading)
                            TaskState.Queued -> stringResource(R.string.status_queued)
                            TaskState.Verifying -> stringResource(R.string.status_verifying)
                            TaskState.Installing -> stringResource(R.string.status_installing)
                            else -> stringResource(R.string.status_confirm)
                        }
                        Row {
                            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (downloading != null && downloading.total > 0) {
                                Text(
                                    dotJoin(ltr("${formatSize(downloading.bytes)} / ${formatSize(downloading.total)}"), ltr("${(downloading.fraction * 100).toInt()}%")),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (downloading != null && downloading.total > 0) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                                color = accent,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                strokeCap = StrokeCap.Round,
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                                color = accent,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                        }
                    }
                    if (row.primaryAction() == PrimaryAction.CANCEL) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onAction) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
            row.status == AppStatus.UPDATE_AVAILABLE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onAction, big.weight(1.4f)) { Text(stringResource(R.string.action_update)) }
                FilledTonalButton(onOpenApp, big.weight(1f)) { Text(stringResource(R.string.action_open)) }
            }
            row.status == AppStatus.NOT_INSTALLED -> Button(onAction, big.fillMaxWidth()) {
                Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                val size = formatSize(row.variant?.size ?: 0)
                Text(dotJoin(stringResource(R.string.action_install), size))
            }
            row.status == AppStatus.UP_TO_DATE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onOpenApp, big.weight(1.4f)) { Text(stringResource(R.string.action_open)) }
                OutlinedButton(onUninstall, big.weight(1f)) { Text(stringResource(R.string.action_uninstall)) }
            }
            else -> NotInstallableActions(row, onDownloadVariant, onOpenUrl)
        }
    }
}

/**
 * What can still be done with an app this device cannot install: save the APK made for
 * another kind of device, or, when there is no APK at all, go to the app's page.
 */
@Composable
private fun NotInstallableActions(row: AppRow, onDownloadVariant: (Variant) -> Unit, onOpenUrl: (String) -> Unit) {
    val big = Modifier.height(52.dp).fillMaxWidth()
    val variants = row.otherVariants
    if (variants.isNotEmpty()) {
        variants.forEachIndexed { index, variant ->
            val label = if (variants.size == 1) stringResource(R.string.action_download_apk)
            else stringResource(R.string.action_apk_for, formFactorLabel(variant.formFactor))
            val content: @Composable RowScope.() -> Unit = {
                Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(dotJoin(label, formatSize(variant.size)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (index == 0) Button(onClick = { onDownloadVariant(variant) }, modifier = big, content = content)
            else FilledTonalButton(onClick = { onDownloadVariant(variant) }, modifier = big, content = content)
        }
        return
    }
    val store = row.app.storeUrl
    val site = row.app.website
    if (store != null) {
        Button(onClick = { onOpenUrl(store) }, modifier = big) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_get_on_play))
        }
    }
    if (site != null) {
        val content: @Composable RowScope.() -> Unit = {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_visit_website))
        }
        if (store == null) Button(onClick = { onOpenUrl(site) }, modifier = big, content = content)
        else FilledTonalButton(onClick = { onOpenUrl(site) }, modifier = big, content = content)
    }
    val repo = row.app.repoUrl
    if (store == null && site == null && repo != null) {
        FilledTonalButton(onClick = { onOpenUrl(repo) }, modifier = big) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_open_github))
        }
    }
}

/** Premium apps: say up front that a license is needed, and how to reach the developer for one. */
@Composable
private fun LicenseCard(license: License, onOpenUrl: (String) -> Unit) {
    val gold = premiumColor()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = gold.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, gold.copy(alpha = 0.40f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFFDB7A), Color(0xFFE39A16)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.WorkspacePremium, null, Modifier.size(26.dp), tint = Color(0xFF3B2600))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.license_required), style = MaterialTheme.typography.titleMedium)
                        Pill(stringResource(R.string.license_premium), gold.copy(alpha = 0.18f), gold)
                    }
                    Spacer(Modifier.height(2.dp))
                    val body = stringResource(R.string.license_body)
                    val hint = stringResource(R.string.license_contact_hint)
                    Text(
                        if (license.contact != null) "$body $hint" else body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            license.contact?.let { TelegramButton(it, stringResource(R.string.license_get_telegram), onOpenUrl) }
        }
    }
}

/** The app's pages outside this app, one tap away on every app's screen. */
@Composable
private fun QuickLinks(row: AppRow, onOpenUrl: (String) -> Unit) {
    val links = listOfNotNull(
        row.app.website?.let { stringResource(R.string.action_website) to it },
        row.app.storeUrl?.let { stringResource(R.string.action_google_play) to it },
        row.app.release?.url?.let { stringResource(R.string.action_releases) to it }
            ?: row.app.repoUrl?.let { stringResource(R.string.action_github) to it },
    )
    if (links.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { (label, url) ->
            AssistChip(
                onClick = { onOpenUrl(url) },
                label = { Text(label) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)) },
                shape = RoundedCornerShape(50),
            )
        }
    }
}

@Composable
private fun Notices(row: AppRow) {
    val text = when (val c = row.compatibility) {
        is Compatibility.Ok -> {
            val have = row.installed
            if (have != null && c.variant.isOlderThan(have)) stringResource(R.string.newer_installed) else null
        }
        Compatibility.NoRelease -> stringResource(R.string.no_release_yet)
        is Compatibility.OtherDevice ->
            if (FormFactor.TV in c.formFactors) stringResource(R.string.incompatible_tv) else stringResource(R.string.incompatible_watch)
        is Compatibility.NeedsNewerAndroid -> stringResource(R.string.incompatible_sdk, androidVersionName(c.minSdk))
        Compatibility.UnsupportedCpu -> stringResource(R.string.incompatible_abi)
    } ?: return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stats(row: AppRow) {
    val variant = row.variant ?: row.app.variants.firstOrNull() ?: return
    val context = LocalContext.current
    val published = parseInstant(row.app.release?.publishedAt)
    val items = listOfNotNull(
        stringResource(R.string.detail_version) to ltr(variant.versionName.ifEmpty { variant.versionCode.toString() }),
        formatSize(variant.size).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.detail_size) to it },
        published?.let { stringResource(R.string.detail_updated) to relativeTime(context, it) },
        stringResource(R.string.detail_requires_android) to ltr("${androidVersionName(variant.minSdk)}+"),
    )
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 14.dp, horizontal = 6.dp)) {
            items.forEach { (label, value) ->
                Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun WhatsNew(row: AppRow) {
    val release = row.app.release ?: return
    val context = LocalContext.current
    val versionName = row.variant?.versionName.orEmpty()
    val sectionTitle = stringResource(R.string.section_whats_new)
    val notes = remember(release.notes, versionName) { trimRedundantHeading(release.notes, listOf(sectionTitle, "What's new", versionName, release.tag.orEmpty(), row.app.name)) }
    val blocks = remember(notes) { parseMarkdown(notes).size }
    var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
    val collapsedBlocks = 6
    Section(
        title = sectionTitle,
        trailing = dotJoin(ltr(versionName.ifEmpty { release.tag.orEmpty() }), relativeTime(context, parseInstant(release.publishedAt))),
    ) {
        if (blocks == 0) {
            Text(stringResource(R.string.no_release_notes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SelectionContainer { MarkdownText(notes, maxBlocks = if (expanded) Int.MAX_VALUE else collapsedBlocks) }
            if (blocks > collapsedBlocks) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
                }
            }
        }
    }
}

@Composable
private fun OtherDevices(row: AppRow, onDownload: (Variant) -> Unit) {
    if (row.otherVariants.isEmpty()) return
    Section(stringResource(R.string.section_other_devices)) {
        row.otherVariants.forEachIndexed { index, variant ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (variant.formFactor == FormFactor.WATCH) Icons.Rounded.Watch else Icons.Rounded.Tv,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(formFactorLabel(variant.formFactor), style = MaterialTheme.typography.titleSmall)
                    Text(
                        dotJoin(ltr(variant.versionName), formatSize(variant.size)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = { onDownload(variant) }) {
                    Icon(Icons.Rounded.Download, stringResource(R.string.action_download_apk))
                }
            }
        }
    }
}

@Composable
private fun DetailsTable(row: AppRow, onOpenUrl: (String) -> Unit) {
    val variant = row.variant ?: row.app.variants.firstOrNull()
    Section(stringResource(R.string.section_details)) {
        @Composable
        fun stacked(label: String, value: String) {
            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                SelectionContainer { Text(ltr(value), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace) }
            }
        }

        @Composable
        fun line(label: String, value: String, mono: Boolean = false, url: String? = null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
                if (url != null) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpenUrl(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        }
                    }
                } else {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = if (mono) FontFamily.Monospace else null,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (variant != null) {
            // A release found ahead of the catalog: its build number is not known yet.
            line(stringResource(R.string.detail_latest_version), ltr(if (variant.live) variant.versionName else "${variant.versionName} (${variant.versionCode})"))
            row.installed?.let { line(stringResource(R.string.detail_installed_version), ltr("${it.versionName} (${it.versionCode})")) }
            if (variant.packageName.length > 22) stacked(stringResource(R.string.detail_package), variant.packageName)
            else line(stringResource(R.string.detail_package), variant.packageName, mono = true)
        }
        line(stringResource(R.string.detail_category), categoryLabel(row.app.category))
        row.app.website?.let { line(stringResource(R.string.detail_website), ltr(it.removePrefix("https://").removePrefix("http://").trimEnd('/')), url = it) }
    }
}

/**
 * Release notes often open with a heading that only repeats what the card already says
 * ("What's new", the version, the app's name). Drops that first line.
 */
internal fun trimRedundantHeading(notes: String, redundant: List<String>): String {
    val text = notes.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").trim()
    val first = text.lineSequence().firstOrNull()?.trim().orEmpty()
    if (!first.startsWith("#")) return text
    val heading = first.trimStart('#').trim().lowercase()
    val repeats = redundant.filter { it.isNotBlank() }.any { word ->
        val w = word.lowercase()
        heading == w || heading == "v$w" || (w.length >= 3 && heading.contains(w) && heading.length <= w.length + 24)
    }
    return if (repeats) text.substringAfter('\n', "").trim() else text
}
