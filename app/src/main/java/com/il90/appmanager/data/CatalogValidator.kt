package com.il90.appmanager.data

import java.io.File
import java.io.IOException
import java.net.URI

/** Why a downloaded catalog was not taken. The last good copy stays in use. */
class CatalogRejected(message: String, val incompatible: Boolean = false) : IllegalArgumentException(message)

/**
 * Checks a catalog before the app uses or saves it.
 *
 * Whole-catalog problems (unreadable JSON, a schema this version does not understand, no apps)
 * reject it, and the last good copy stays. Problems in one app (no id, a duplicate id, a
 * download that is not https) only drop that app or that download: one bad entry must not
 * hide every other app. Optional media that is not https is dropped quietly.
 */
object CatalogValidator {
    /** The newest catalog schema this version of the app understands. */
    const val MAX_SCHEMA = 2

    private val ID = Regex("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** Parses and validates [text]. Throws [CatalogRejected] when it cannot be used at all. */
    fun parse(text: String): Catalog {
        val parsed = try {
            CatalogJson.decodeFromString(Catalog.serializer(), text)
        } catch (e: Exception) {
            throw CatalogRejected("not a readable catalog: ${e.message?.take(120)}")
        }
        return validate(parsed)
    }

    fun validate(catalog: Catalog): Catalog {
        if (catalog.version > MAX_SCHEMA) {
            throw CatalogRejected("catalog schema ${catalog.version} needs a newer version of the app", incompatible = true)
        }
        if (catalog.version < 1) throw CatalogRejected("catalog schema ${catalog.version} is not valid")
        val seen = HashSet<String>()
        val apps = catalog.apps.mapNotNull { app ->
            if (!ID.matches(app.id) || app.name.isBlank() || !seen.add(app.id)) return@mapNotNull null
            app.copy(
                variants = app.variants.filter { isHttps(it.url) && it.packageName.isNotBlank() && (it.sha256.isEmpty() || SHA256.matches(it.sha256)) },
                screenshots = app.screenshots.filter(::isHttps).take(10),
                icon = app.icon?.takeIf { isHttps(it) || isRelativeAsset(it) },
                website = app.website?.takeIf(::isHttps),
                storeUrl = app.storeUrl?.takeIf(::isHttps),
                repoUrl = app.repoUrl?.takeIf(::isHttps),
                latestUrl = app.latestUrl?.takeIf(::isHttps),
                categories = app.categories.filter { it.isNotBlank() }.distinct(),
                license = app.license?.let { it.copy(contact = it.contact?.takeIf(::isHttps)) },
            )
        }
        if (apps.isEmpty()) throw CatalogRejected("the catalog has no usable apps")
        return catalog.copy(
            apps = apps,
            categories = catalog.categories.filter { ID.matches(it.id) }.distinctBy { it.id },
            contact = catalog.contact?.let { it.copy(telegram = it.telegram?.takeIf(::isHttps)) },
        )
    }

    fun isHttps(url: String?): Boolean {
        if (url.isNullOrBlank() || url.length > 2048) return false
        return try {
            val uri = URI(url)
            uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null
        } catch (_: Exception) {
            false
        }
    }

    /** An icon path relative to the catalog's baseUrl, such as "icon-x-0123abcd.png" or "icons/x.png?v=1". */
    private fun isRelativeAsset(path: String): Boolean =
        !path.contains(':') && !path.startsWith("/") && !path.contains("..") && !path.contains('\\')
}

/**
 * The last good catalog on disk: written atomically, read back only if it still validates.
 * Offline, the app keeps working from this copy.
 */
class CatalogCache(private val file: File) {
    fun read(): Catalog? = try {
        if (file.exists()) CatalogValidator.parse(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    /** Saves [text], which must already have passed [CatalogValidator.parse]. */
    fun write(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (_: IOException) {
            tmp.delete() // Only a cache: the app still has the catalog in memory.
        }
    }

    fun exists(): Boolean = file.exists()
    fun lastModified(): Long = file.lastModified()
    fun touch() = file.setLastModified(System.currentTimeMillis())
}
