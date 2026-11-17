package com.il90.appmanager.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/** A file of a release, as found on GitHub. */
@Serializable
data class LiveAsset(val name: String, val size: Long = 0, val url: String, val sha256: String = "")

/** A release found on GitHub by the app itself, cut down to what can be offered for download. */
@Serializable
data class LiveRelease(
    val tag: String,
    val name: String? = null,
    val notes: String = "",
    val publishedAt: String? = null,
    val url: String? = null,
    val assets: List<LiveAsset> = emptyList(),
)

/** A release the way the GitHub API describes it; only what is needed here. */
@Serializable
internal data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
internal data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
    /** "sha256:<hex>", which GitHub computes for every uploaded file. */
    val digest: String? = null,
    val state: String = "uploaded",
)

private fun pattern(text: String?, ignoreCase: Boolean = false): Regex? = try {
    if (text.isNullOrEmpty()) null else if (ignoreCase) Regex(text, RegexOption.IGNORE_CASE) else Regex(text)
} catch (_: IllegalArgumentException) {
    null
}

internal fun Source.matchesTag(candidate: String): Boolean {
    if (tag.isNullOrEmpty()) return true
    // A rule that cannot be read is no licence to offer anything.
    return pattern(tag)?.containsMatchIn(candidate) ?: false
}

/** The files of this release that [source] offers, or null when it is not a release to offer. */
internal fun GhRelease.toLive(source: Source): LiveRelease? {
    if (draft || (prerelease && !source.prerelease)) return null
    if (!source.matchesTag(tagName)) return null
    val wanted = pattern(source.asset ?: "\\.apk$", ignoreCase = true) ?: return null
    val unwanted = pattern(source.exclude, ignoreCase = true)
    val files = assets.filter {
        it.state == "uploaded" && it.url.isNotEmpty() && wanted.containsMatchIn(it.name) && unwanted?.containsMatchIn(it.name) != true
    }
    if (files.isEmpty()) return null
    // Several names for one file (App.apk next to App-1.2.3.apk): keep the versioned one.
    val unique = files.groupBy { it.size }.map { (_, same) -> same.maxBy { it.name.length } }.sortedBy { it.name }
    return LiveRelease(
        tag = tagName,
        name = name,
        notes = body.orEmpty(),
        publishedAt = publishedAt,
        url = htmlUrl,
        assets = unique.map { LiveAsset(it.name, it.size, it.url, it.digest?.removePrefix("sha256:")?.takeIf { hex -> hex.length == 64 }.orEmpty()) },
    )
}

/** The newest release of [source] among [releases]. */
internal fun pickLatest(releases: List<GhRelease>, source: Source): LiveRelease? =
    releases.mapNotNull { it.toLive(source) }.maxByOrNull { it.publishedAt.orEmpty() }

private val VERSION = Regex("\\d+(?:\\.\\d+)*")
private val DOTTED_VERSION = Regex("\\d+(?:\\.\\d+)+")

/** A file name with its numbers taken out, so the same file of two releases compares equal. */
internal fun shapeOf(fileName: String): String = VERSION.replace(fileName, "#")

/**
 * The version a new file carries: read from the place the version has in the old file's name,
 * or else from the release tag.
 */
internal fun liveVersionName(template: Variant, fileName: String, tag: String): String {
    val at = if (template.versionName.isEmpty()) -1 else template.fileName.indexOf(template.versionName)
    if (at >= 0) {
        val before = template.fileName.substring(0, at)
        val after = template.fileName.substring(at + template.versionName.length)
        if (fileName.length > before.length + after.length && fileName.startsWith(before) && fileName.endsWith(after)) {
            return fileName.substring(before.length, fileName.length - after.length)
        }
    }
    return DOTTED_VERSION.find(tag)?.value ?: VERSION.find(tag)?.value ?: tag
}

/**
 * The files of [release] as variants, each modelled on the catalog's variant for the same kind
 * of file (device, CPU, package). Null unless every known variant has exactly one counterpart:
 * a half-new list of files would be worse than a slightly old one.
 */
internal fun liveVariants(templates: List<Variant>, release: LiveRelease): List<Variant>? {
    if (templates.isEmpty() || release.assets.isEmpty()) return null
    fun make(template: Variant, asset: LiveAsset) = template.copy(
        fileName = asset.name,
        url = asset.url,
        size = asset.size,
        sha256 = asset.sha256,
        versionName = liveVersionName(template, asset.name, release.tag),
        live = true,
    )
    if (templates.size == 1 && release.assets.size == 1) return listOf(make(templates[0], release.assets[0]))
    if (templates.map { shapeOf(it.fileName) }.distinct().size != templates.size) return null
    val byShape = release.assets.groupBy { shapeOf(it.name) }
    return templates.map { template ->
        val same = byShape[shapeOf(template.fileName)] ?: return null
        if (same.size != 1) return null
        make(template, same[0])
    }
}

internal fun isLater(a: String?, b: String?): Boolean {
    val x = a?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    val y = b?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
    return x.isAfter(y)
}

/** This app with [live] in place of the catalog's release, when [live] is really the newer one. */
internal fun CatalogApp.withLive(live: LiveRelease?): CatalogApp {
    if (live == null || live.tag == release?.tag) return this
    if (!isLater(live.publishedAt, release?.publishedAt)) return this
    val fresh = liveVariants(variants, live) ?: return this
    return copy(
        release = Release(tag = live.tag, name = live.name ?: live.tag, notes = live.notes, publishedAt = live.publishedAt, url = live.url),
        variants = fresh,
    )
}

/**
 * Keeps the list ahead of the catalog. The catalog is rebuilt by a workflow that can take hours
 * to notice a release; this asks each app's releases page directly, so what is offered is always
 * the newest version.
 *
 * It is cheap on purpose. Whether anything changed is answered by where
 * github.com/<repo>/releases/latest redirects to, which is an ordinary page and not the
 * rate-limited API. The API is asked only about a release the catalog does not know yet, once.
 */
class LiveReleases(context: Context, http: OkHttpClient) {
    @Serializable
    private data class Seen(
        val repo: String = "",
        /** Where releases/latest pointed when it was last looked at. */
        val latestTag: String? = null,
        val checkedAt: Long = 0,
        /** That release, when it is one of this app's and can be offered. */
        val release: LiveRelease? = null,
    )

    private val file = File(context.filesDir, "live-releases.json")
    private val store = MapSerializer(String.serializer(), Seen.serializer())
    private val pages = http.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()
    private val api = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val mutex = Mutex()

    @Volatile
    private var seen: Map<String, Seen> = read()

    /** The API said "too many requests": leave it alone until then. */
    @Volatile
    private var apiClosedUntil = 0L

    /** [catalog] with every app moved up to the newest release that is known. Reads nothing. */
    fun apply(catalog: Catalog): Catalog =
        catalog.copy(apps = catalog.apps.map { app -> app.withLive(seen[app.id]?.takeIf { it.repo == app.source?.repo }?.release) })

    /**
     * Looks at the releases pages of the apps in [catalog] (of [only], when given) that were not
     * looked at in the last [maxAgeMs]. Never throws: with no network the last answers stay.
     */
    suspend fun refresh(catalog: Catalog, maxAgeMs: Long, only: String? = null) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val due = catalog.apps.filter { app ->
                val source = app.source
                source != null && app.variants.isNotEmpty() && (only == null || app.id == only) &&
                    now - (seen[app.id]?.takeIf { it.repo == source.repo }?.checkedAt ?: 0L) >= maxAgeMs
            }
            if (due.isEmpty()) return@withContext
            val gate = Semaphore(6)
            val answers = coroutineScope {
                due.map { app -> async { gate.withPermit { app.id to look(app) } } }.awaitAll()
            }
            val next = seen.toMutableMap()
            for ((id, answer) in answers) if (answer != null) next[id] = answer
            if (only == null) next.keys.retainAll(catalog.apps.mapTo(HashSet()) { it.id })
            seen = next
            write(next)
        }
    }

    /** @return what is now known about [app]'s releases, or null when the network did not answer. */
    private fun look(app: CatalogApp): Seen? {
        val source = app.source ?: return null
        val before = seen[app.id]?.takeIf { it.repo == source.repo }
        val now = System.currentTimeMillis()
        return try {
            val latest = latestTag(source.repo)
            when {
                latest == null -> Seen(source.repo, null, now, null)
                // Nothing came out since the last look: what was found then still stands.
                before != null && latest == before.latestTag -> before.copy(checkedAt = now)
                // The catalog has caught up with it.
                latest == app.release?.tag -> Seen(source.repo, latest, now, null)
                else -> {
                    val direct = if (source.matchesTag(latest)) release(source.repo, latest)?.toLive(source) else null
                    // A repository that publishes other things too: its newest release is not this app's.
                    Seen(source.repo, latest, now, direct ?: pickLatest(releases(source.repo), source))
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null // an answer that could not be read
        }
    }

    /** The tag github.com/<repo>/releases/latest leads to, or null when there is no release. */
    private fun latestTag(repo: String): String? {
        val request = Request.Builder().url("https://github.com/$repo/releases/latest").head().build()
        pages.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (response.code !in 300..399) throw IOException("HTTP ${response.code}")
            val target = response.header("Location") ?: return null
            val marker = "/releases/tag/"
            val at = target.indexOf(marker)
            // With no release at all the page leads back to the (empty) list.
            return if (at < 0) null else URLDecoder.decode(target.substring(at + marker.length), "UTF-8")
        }
    }

    private fun release(repo: String, tag: String): GhRelease? =
        ask("https://api.github.com/repos/$repo/releases/tags/${URLEncoder.encode(tag, "UTF-8").replace("+", "%20")}")
            ?.let { CatalogJson.decodeFromString(GhRelease.serializer(), it) }

    private fun releases(repo: String): List<GhRelease> =
        ask("https://api.github.com/repos/$repo/releases?per_page=30")
            ?.let { CatalogJson.decodeFromString(ListSerializer(GhRelease.serializer()), it) }
            .orEmpty()

    /** @return the answer's text, or null for "no such thing". */
    private fun ask(url: String): String? {
        if (System.currentTimeMillis() < apiClosedUntil) throw IOException("rate limited")
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        api.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (response.code == 403 || response.code == 429) {
                // Without an account the API allows a few dozen questions an hour per network.
                val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                apiClosedUntil = maxOf(reset ?: 0L, System.currentTimeMillis() + 5 * 60 * 1000)
                throw IOException("rate limited")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("empty body")
        }
    }

    private fun read(): Map<String, Seen> = try {
        if (file.exists()) CatalogJson.decodeFromString(store, file.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun write(value: Map<String, Seen>) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(CatalogJson.encodeToString(store, value))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: IOException) {
            // Only a cache: it is asked again next time.
        }
    }
}
