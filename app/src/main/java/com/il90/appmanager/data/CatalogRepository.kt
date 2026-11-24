package com.il90.appmanager.data

import android.content.Context
import com.il90.appmanager.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class CatalogState(
    val catalog: Catalog? = null,
    /** True while the first load has neither a saved list nor an answer from the network. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** The last refresh failed, so what is shown may be out of date. */
    val offline: Boolean = false,
    /** The published catalog needs a newer version of this app; the last compatible copy is shown. */
    val incompatible: Boolean = false,
    val fetchedAt: Long = 0,
)

/**
 * Loads the catalog and keeps the last good copy on disk. What it hands out is that catalog
 * with every app moved up to its newest release ([LiveReleases]).
 *
 * The catalog is the `catalog.json` asset of the fixed `catalog` GitHub Release, which the
 * Catalog workflow replaces only after it validated it; the app validates it again
 * ([CatalogValidator]) before using or saving it. A copy on the `catalog` branch (raw GitHub
 * and jsDelivr) is the fallback. Nothing is rebuilt into the app: a new catalog is picked up on
 * the next refresh.
 */
class CatalogRepository(context: Context, private val http: OkHttpClient, private val live: LiveReleases) {
    private val cache = CatalogCache(File(context.filesDir, "catalog.json"))
    /** The catalog as published, before anything newer is laid over it. */
    @Volatile
    private var published: Catalog? = null
    private val etagFile = File(context.filesDir, "catalog.etag")
    private val mutex = Mutex()
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val urls = catalogUrls(BuildConfig.CATALOG_REPO, BuildConfig.CATALOG_TAG, BuildConfig.CATALOG_BRANCH)

    /** Shows the saved list at once (if any), then asks the network. */
    suspend fun load() {
        withContext(Dispatchers.IO) {
            cache.read()?.let { saved ->
                published = saved
                _state.value = _state.value.copy(catalog = live.apply(saved), loading = false, fetchedAt = cache.lastModified())
            }
        }
        refresh()
    }

    /**
     * @param force the user asked for it: look at the releases pages again even if they were
     * looked at a moment ago
     * @return true when the network answered (even with "nothing new").
     */
    suspend fun refresh(force: Boolean = false): Boolean = mutex.withLock {
        _state.value = _state.value.copy(refreshing = true)
        val ok = withContext(Dispatchers.IO) {
            var result: Catalog? = null
            var answered = false
            var incompatible = false
            for ((index, url) in urls.withIndex()) {
                try {
                    val fresh = fetch(url, useEtag = index == 0 && cache.exists())
                    answered = true
                    result = fresh
                    break
                } catch (_: IOException) {
                    // try the next mirror
                } catch (e: CatalogRejected) {
                    // unreadable or invalid answer: keep the last good copy, try the next mirror
                    incompatible = incompatible || e.incompatible
                }
            }
            if (result != null) {
                published = result
                _state.value = _state.value.copy(catalog = live.apply(result), loading = false, incompatible = false, fetchedAt = System.currentTimeMillis())
            } else if (answered) {
                cache.touch()
                _state.value = _state.value.copy(fetchedAt = System.currentTimeMillis(), incompatible = false)
            } else {
                _state.value = _state.value.copy(incompatible = incompatible)
            }
            answered
        }
        // The catalog can be hours behind a release: ask the releases pages themselves.
        val current = published
        if (ok && current != null) {
            live.refresh(current, maxAgeMs = if (force) FORCED_GAP_MS else USUAL_GAP_MS)
            _state.value = _state.value.copy(catalog = live.apply(current))
        }
        _state.value = _state.value.copy(loading = false, refreshing = false, offline = !ok)
        ok
    }

    /**
     * Looks at one app's releases page now, for the moment before it is installed, and returns
     * the app as it should be installed. Waits a few seconds at most.
     */
    suspend fun freshest(appId: String): CatalogApp? {
        val current = published ?: return null
        withTimeoutOrNull(6_000) { live.refresh(current, maxAgeMs = JUST_NOW_MS, only = appId) }
        val effective = live.apply(current)
        if (published === current) _state.value = _state.value.copy(catalog = effective)
        return effective.apps.firstOrNull { it.id == appId }
    }

    /** @return the new catalog, or null when the server says the saved one is current. */
    private fun fetch(url: String, useEtag: Boolean): Catalog? {
        val request = Request.Builder().url(url).apply {
            val etag = if (useEtag && etagFile.exists()) etagFile.readText().trim() else ""
            if (etag.isNotEmpty()) header("If-None-Match", etag)
            header("Accept", "application/json")
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 304) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("empty body")
            // Validated before it replaces the last good copy.
            val parsed = CatalogValidator.parse(body)
            cache.write(body)
            val etag = response.header("ETag")
            if (etag != null && url == urls.first()) etagFile.writeText(etag) else etagFile.delete()
            return parsed
        }
    }

    private companion object {
        const val USUAL_GAP_MS = 3 * 60 * 1000L
        const val FORCED_GAP_MS = 15 * 1000L
        const val JUST_NOW_MS = 30 * 1000L
    }
}

/**
 * Where the catalog is downloaded from, in order: the asset of the fixed catalog release (the
 * source of truth), then the mirror on the catalog branch, directly and through jsDelivr.
 */
fun catalogUrls(repo: String, tag: String, branch: String): List<String> = listOf(
    "https://github.com/$repo/releases/download/$tag/catalog.json",
    "https://raw.githubusercontent.com/$repo/refs/heads/$branch/catalog.json",
    "https://cdn.jsdelivr.net/gh/$repo@$branch/catalog.json",
)
