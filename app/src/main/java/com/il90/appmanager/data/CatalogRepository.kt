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
    val fetchedAt: Long = 0,
)

/** Loads the catalog from the public repository and keeps the last good copy on disk. */
class CatalogRepository(context: Context, private val http: OkHttpClient) {
    private val file = File(context.filesDir, "catalog.json")
    private val etagFile = File(context.filesDir, "catalog.etag")
    private val mutex = Mutex()
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val urls = listOf(
        "https://raw.githubusercontent.com/${BuildConfig.CATALOG_REPO}/${BuildConfig.CATALOG_BRANCH}/catalog.json",
        "https://cdn.jsdelivr.net/gh/${BuildConfig.CATALOG_REPO}@${BuildConfig.CATALOG_BRANCH}/catalog.json",
    )

    /** Shows the saved list at once (if any), then asks the network. */
    suspend fun load() {
        withContext(Dispatchers.IO) {
            readSaved()?.let { saved ->
                _state.value = _state.value.copy(catalog = saved, loading = false, fetchedAt = file.lastModified())
            }
        }
        refresh()
    }

    /** @return true when the network answered (even with "nothing new"). */
    suspend fun refresh(): Boolean = mutex.withLock {
        _state.value = _state.value.copy(refreshing = true)
        val ok = withContext(Dispatchers.IO) {
            var result: Catalog? = null
            var answered = false
            for ((index, url) in urls.withIndex()) {
                try {
                    val fresh = fetch(url, useEtag = index == 0 && file.exists())
                    answered = true
                    result = fresh
                    break
                } catch (_: IOException) {
                    // try the next mirror
                } catch (_: IllegalArgumentException) {
                    // unreadable answer: try the next mirror
                }
            }
            if (result != null) {
                _state.value = _state.value.copy(catalog = result, fetchedAt = System.currentTimeMillis())
            } else if (answered) {
                file.setLastModified(System.currentTimeMillis())
                _state.value = _state.value.copy(fetchedAt = System.currentTimeMillis())
            }
            answered
        }
        _state.value = _state.value.copy(loading = false, refreshing = false, offline = !ok)
        ok
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
            val parsed = try {
                CatalogJson.decodeFromString(Catalog.serializer(), body)
            } catch (e: Exception) {
                throw IllegalArgumentException("bad catalog", e)
            }
            require(parsed.apps.isNotEmpty()) { "empty catalog" }
            val tmp = File(file.parentFile, "catalog.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.writeText(body)
                tmp.delete()
            }
            val etag = response.header("ETag")
            if (etag != null && url == urls.first()) etagFile.writeText(etag) else etagFile.delete()
            return parsed
        }
    }

    private fun readSaved(): Catalog? = try {
        if (file.exists()) CatalogJson.decodeFromString(Catalog.serializer(), file.readText()) else null
    } catch (_: Exception) {
        null
    }
}
