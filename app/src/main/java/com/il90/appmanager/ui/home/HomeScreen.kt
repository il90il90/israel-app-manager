package com.il90.appmanager.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowCircleUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.il90.appmanager.R
import com.il90.appmanager.data.localized
import com.il90.appmanager.install.TaskState
import com.il90.appmanager.ui.AppRow
import com.il90.appmanager.ui.AppStatus
import com.il90.appmanager.ui.Filter
import com.il90.appmanager.ui.HomeState
import com.il90.appmanager.ui.PrimaryAction
import com.il90.appmanager.ui.components.ActionButton
import com.il90.appmanager.ui.components.AppIcon
import com.il90.appmanager.ui.components.Pill
import com.il90.appmanager.ui.components.categoryLabel
import com.il90.appmanager.ui.components.failureMessage
import com.il90.appmanager.ui.components.unavailableLabel
import com.il90.appmanager.ui.primaryAction
import com.il90.appmanager.util.appLanguage
import com.il90.appmanager.util.dotJoin
import com.il90.appmanager.util.formatSize
import com.il90.appmanager.util.isolate
import com.il90.appmanager.util.ltr
import com.il90.appmanager.util.parseColor
import com.il90.appmanager.util.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    onOpen: (AppRow) -> Unit,
    onAction: (AppRow) -> Unit,
    onUpdateAll: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onDismissAnnouncement: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    snackbar: SnackbarHostState,
) {
    val language = appLanguage()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.refreshing) { if (!state.refreshing) pulled = false }

    val gridState = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 120 } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val visible = remember(state.rows, filter, query, language) {
        val needle = query.trim().lowercase()
        state.rows.asSequence()
            .filter { row ->
                when (filter) {
                    Filter.ALL -> true
                    Filter.INSTALLED -> row.installed != null
                    Filter.UPDATES -> row.status == AppStatus.UPDATE_AVAILABLE
                }
            }
            .filter { row ->
                needle.isEmpty() || row.app.name.lowercase().contains(needle) ||
                    row.app.tagline.localized(language).lowercase().contains(needle) ||
                    row.app.category.lowercase().contains(needle) ||
                    row.app.variants.any { it.packageName.lowercase().contains(needle) || it.label.lowercase().contains(needle) }
            }
            .sortedWith(compareBy({ if (it.status == AppStatus.UPDATE_AVAILABLE) 0 else 1 }, { it.app.order }))
            .toList()
    }
    val browsing = query.isBlank() && filter == Filter.ALL

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                title = {
                    if (searching) {
                        SearchField(query, onQuery = { query = it })
                    } else {
                        AnimatedVisibility(scrolled, enter = fadeIn(), exit = fadeOut()) {
                            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                actions = {
                    if (searching) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) }
                        }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, stringResource(R.string.action_search)) }
                        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.action_settings)) }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pulled && state.refreshing,
            onRefresh = { pulled = true; onRefresh() },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 330.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom + 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!searching) {
                    fullWidth("title") { Header(state) }
                }
                when {
                    state.loading -> items(6, key = { "skeleton$it" }) { SkeletonCard() }
                    state.rows.isEmpty() -> fullWidth("error") { ErrorState(onRefresh) }
                    else -> {
                        if (state.offline) fullWidth("offline") { OfflineBanner() }
                        val self = state.selfUpdate
                        if (self != null && browsing) fullWidth("self") { SelfUpdateBanner(self, onAction = { onAction(self) }) }
                        val note = state.announcement
                        if (note != null && browsing) {
                            fullWidth("announcement") {
                                AnnouncementCard(
                                    title = note.title.localized(language),
                                    body = note.body.localized(language),
                                    onOpen = note.url?.let { url -> { onOpenUrl(url) } },
                                    onDismiss = { onDismissAnnouncement(note.id) },
                                )
                            }
                        }
                        val updates = state.updates
                        if (updates.isNotEmpty() && browsing) {
                            fullWidth("updates") { UpdatesCard(updates, onUpdateAll, onShow = { onFilter(Filter.UPDATES) }) }
                        }
                        val featured = state.rows.filter { it.app.featured }
                        if (featured.size >= 2 && browsing && !searching) {
                            fullWidth("featured") { FeaturedRow(featured, language, onOpen, onAction) }
                        }
                        fullWidth("filters") {
                            FilterRow(filter, onFilter, state.rows.size, state.installedCount, updates.size)
                        }
                        if (visible.isEmpty()) {
                            fullWidth("empty") { EmptyState(filter, query) }
                        }
                        items(visible, key = { it.id }) { row ->
                            AppCard(row, language, onOpen = { onOpen(row) }, onAction = { onAction(row) }, modifier = Modifier.animateItem())
                        }
                        if (state.fetchedAt > 0 && browsing) {
                            fullWidth("footer") {
                                Text(
                                    stringResource(R.string.catalog_updated, relativeTime(LocalContext.current, state.fetchedAt)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

@Composable
private fun Header(state: HomeState) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        if (state.rows.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                pluralStringResource(R.plurals.home_subtitle_apps, state.rows.size, state.rows.size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        if (query.isEmpty()) {
            Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontWeight = FontWeight.Normal)
        }
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Normal),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
}

@Composable
private fun FilterRow(filter: Filter, onFilter: (Filter) -> Unit, all: Int, installed: Int, updates: Int) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Composable
        fun chip(target: Filter, label: String, count: Int) {
            FilterChip(
                selected = filter == target,
                onClick = { onFilter(target) },
                label = { Text(if (count > 0) "$label  $count" else label) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
        chip(Filter.ALL, stringResource(R.string.filter_all), all)
        chip(Filter.INSTALLED, stringResource(R.string.filter_installed), installed)
        chip(Filter.UPDATES, stringResource(R.string.filter_updates), updates)
    }
}

@Composable
fun AppCard(row: AppRow, language: String, onOpen: () -> Unit, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 14.dp, bottom = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.iconUrl, row.app.name, row.app.accent, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val tagline = row.app.tagline.localized(language)
                if (tagline.isNotEmpty()) {
                    Text(
                        tagline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                StatusLine(row)
            }
            Spacer(Modifier.width(8.dp))
            if (row.status == AppStatus.UNAVAILABLE && row.task == null) {
                Pill(unavailableLabel(row.compatibility), MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ActionButton(row, onAction)
            }
        }
    }
}

@Composable
fun StatusLine(row: AppRow, modifier: Modifier = Modifier) {
    val small = MaterialTheme.typography.labelMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    val variant = row.variant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val state = row.task?.state) {
            is TaskState.Downloading -> Text(
                if (state.total > 0) ltr("${formatSize(state.bytes)} / ${formatSize(state.total)}") else stringResource(R.string.status_downloading),
                style = small, color = MaterialTheme.colorScheme.primary, maxLines = 1,
            )
            TaskState.Queued -> Text(stringResource(R.string.status_queued), style = small, color = MaterialTheme.colorScheme.primary)
            TaskState.Verifying -> Text(stringResource(R.string.status_verifying), style = small, color = MaterialTheme.colorScheme.primary)
            TaskState.Installing -> Text(stringResource(R.string.status_installing), style = small, color = MaterialTheme.colorScheme.primary)
            TaskState.AwaitingUser -> Text(stringResource(R.string.status_confirm), style = small, color = MaterialTheme.colorScheme.primary)
            is TaskState.Failed -> Text(failureMessage(state.reason), style = small, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            null -> when (row.status) {
                AppStatus.UPDATE_AVAILABLE -> {
                    Icon(Icons.Rounded.ArrowCircleUp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.state_update_from_to, ltr(row.installed?.versionName.orEmpty()), ltr(variant?.versionName.orEmpty())),
                        style = small, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                AppStatus.UP_TO_DATE -> {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Text(
                        stringResource(R.string.state_installed_version, ltr(row.installed?.versionName.orEmpty())),
                        style = small, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                AppStatus.NOT_INSTALLED -> Text(
                    dotJoin(ltr(variant?.versionName.orEmpty()), formatSize(variant?.size ?: 0)),
                    style = small, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                AppStatus.UNAVAILABLE -> Text(categoryLabel(row.app.category), style = small, color = muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun UpdatesCard(updates: List<AppRow>, onUpdateAll: () -> Unit, onShow: () -> Unit) {
    Surface(onClick = onShow, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width((36 + 22 * (minOf(updates.size, 3) - 1)).dp)) {
                updates.take(3).forEachIndexed { i, row ->
                    AppIcon(row.iconUrl, row.app.name, row.app.accent, 36.dp, Modifier.offset(x = (22 * i).dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.updates_available, updates.size, updates.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    updates.joinToString(", ") { isolate(it.app.name) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val allBusy = updates.all { it.isBusy }
            Button(onClick = onUpdateAll, enabled = !allBusy, contentPadding = PaddingValues(horizontal = 16.dp)) {
                Text(stringResource(R.string.action_update_all), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SelfUpdateBanner(row: AppRow, onAction: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.self_update_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    stringResource(R.string.self_update_body, ltr(row.variant?.versionName.orEmpty()), formatSize(row.variant?.size ?: 0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.width(8.dp))
            ActionButton(row, onAction)
        }
    }
}

@Composable
private fun AnnouncementCard(title: String, body: String, onOpen: (() -> Unit)?, onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f))
                if (onOpen != null) TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.action_more)) }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.action_dismiss), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeaturedRow(rows: List<AppRow>, language: String, onOpen: (AppRow) -> Unit, onAction: (AppRow) -> Unit) {
    Column {
        Text(stringResource(R.string.featured), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp, top = 4.dp))
        // The row bleeds to the screen edges; the grid's own padding is undone and re-applied as content padding.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth().bleed(16.dp),
        ) {
            items(rows, key = { it.id }) { row -> FeaturedCard(row, language, onOpen = { onOpen(row) }, onAction = { onAction(row) }) }
        }
    }
}

/** Lets a child ignore [padding] of horizontal padding applied by its parent. */
private fun Modifier.bleed(padding: Dp): Modifier = layout { measurable, constraints ->
    val extra = padding.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-padding.roundToPx(), 0) }
}

@Composable
private fun FeaturedCard(row: AppRow, language: String, onOpen: () -> Unit, onAction: () -> Unit) {
    val accent = parseColor(row.app.accent) ?: MaterialTheme.colorScheme.primary
    val gradient = Brush.linearGradient(listOf(lerp(accent, Color.Black, 0.30f), lerp(accent, Color(0xFF05070C), 0.78f)))
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = Color.Transparent, modifier = Modifier.width(292.dp)) {
        Column(Modifier.background(gradient).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(row.iconUrl, row.app.name, row.app.accent, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.app.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(categoryLabel(row.app.category), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                row.app.tagline.localized(language),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.88f),
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val caption = when {
                    row.task != null -> stringResource(R.string.status_downloading)
                    row.status == AppStatus.UP_TO_DATE -> stringResource(R.string.state_installed)
                    row.status == AppStatus.UPDATE_AVAILABLE -> stringResource(R.string.state_update_available)
                    row.variant != null -> dotJoin(ltr(row.variant.versionName), formatSize(row.variant.size))
                    else -> unavailableLabel(row.compatibility)
                }
                Text(caption, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val action = row.primaryAction()
                val label = when (action) {
                    PrimaryAction.INSTALL -> stringResource(R.string.action_install)
                    PrimaryAction.UPDATE -> stringResource(R.string.action_update)
                    PrimaryAction.OPEN -> stringResource(R.string.action_open)
                    PrimaryAction.CANCEL -> stringResource(R.string.action_cancel)
                    PrimaryAction.RETRY -> stringResource(R.string.action_retry)
                    PrimaryAction.NONE -> null
                }
                if (label != null) {
                    Button(
                        onClick = onAction,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF10131A)),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        modifier = Modifier.height(38.dp),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SkeletonCard() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "alpha")
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp).alpha(alpha), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(27)).background(block))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(130.dp).height(16.dp).clip(CircleShape).background(block))
                Box(Modifier.fillMaxWidth(0.85f).height(12.dp).clip(CircleShape).background(block))
                Box(Modifier.width(90.dp).height(10.dp).clip(CircleShape).background(block))
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(76.dp).height(40.dp).clip(CircleShape).background(block))
        }
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CloudOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.error_offline_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.error_offline_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
    }
}

@Composable
private fun EmptyState(filter: Filter, query: String) {
    val (title, body) = when {
        query.isNotBlank() -> stringResource(R.string.empty_search, query.trim()) to ""
        filter == Filter.UPDATES -> stringResource(R.string.empty_updates_title) to stringResource(R.string.empty_updates_body)
        else -> stringResource(R.string.empty_installed_title) to stringResource(R.string.empty_installed_body)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (filter == Filter.UPDATES && query.isBlank()) {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(14.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
