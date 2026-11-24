package com.il90.appmanager.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The remote catalog, as written by tools/catalog/build_catalog.py and published as the
 * `catalog.json` asset of the fixed `catalog` GitHub Release.
 */
@Serializable
data class Catalog(
    /** Catalogs before version 2 call it `schema`; see [version]. */
    val schemaVersion: Int? = null,
    val schema: Int = 1,
    val generatedAt: String? = null,
    val baseUrl: String = "",
    val dashboardUrl: String? = null,
    val announcement: Announcement? = null,
    val contact: Contact? = null,
    /** The category registry: ids with their localized names. */
    val categories: List<CategoryDef> = emptyList(),
    val apps: List<CatalogApp> = emptyList(),
) {
    val version: Int get() = schemaVersion ?: schema
}

/** One entry of the category registry. */
@Serializable
data class CategoryDef(val id: String, val name: Map<String, String> = emptyMap())

const val DEFAULT_CATEGORY = "other"

/** How to reach the developer. */
@Serializable
data class Contact(val telegram: String? = null)

/** A premium app: it is installed like any other, but needs a license from the developer to be used. */
@Serializable
data class License(
    val required: Boolean = true,
    /** Where to ask for a license (a Telegram link). */
    val contact: String? = null,
)

@Serializable
data class Announcement(
    val id: String = "",
    val title: Map<String, String> = emptyMap(),
    val body: Map<String, String> = emptyMap(),
    val url: String? = null,
)

@Serializable
data class CatalogApp(
    val id: String,
    val name: String,
    val tagline: Map<String, String>? = null,
    val description: Map<String, String>? = null,
    /** "link" (resolved from a GitHub repository) or "custom" (written by the catalog owner). */
    val type: String = "custom",
    /** The first of [categories]; the only one catalogs before version 2 have. */
    val category: String = DEFAULT_CATEGORY,
    val categories: List<String> = emptyList(),
    val developer: String = "",
    val website: String? = null,
    /** A store page (Google Play) for apps that are not installed from here. */
    val storeUrl: String? = null,
    val repoUrl: String? = null,
    /** A GitHub link that always leads to the newest version. */
    val latestUrl: String? = null,
    /** Where the app's releases are published; see LiveReleases. */
    val source: Source? = null,
    val featured: Boolean = false,
    val license: License? = null,
    val self: Boolean = false,
    val order: Int = 0,
    val icon: String? = null,
    val iconStyle: String? = null,
    val accent: String? = null,
    val release: Release? = null,
    val variants: List<Variant> = emptyList(),
    /** Optional https links to screenshots; none is fine. */
    val screenshots: List<String> = emptyList(),
) {
    val needsLicense: Boolean get() = license?.required == true

    /** Every category of this app, never empty. */
    val allCategories: List<String> get() = categories.ifEmpty { listOf(category.ifBlank { DEFAULT_CATEGORY }) }
}

/** The releases of one app on GitHub, with the same rules the catalog builder uses to pick one. */
@Serializable
data class Source(
    val repo: String,
    /** Regular expressions: which tags are this app's, which files to offer, which to leave out. */
    val tag: String? = null,
    val asset: String? = null,
    val exclude: String? = null,
    val prerelease: Boolean = false,
)

@Serializable
data class Release(
    val tag: String? = null,
    val name: String? = null,
    val notes: String = "",
    val publishedAt: String? = null,
    val url: String? = null,
)

@Serializable
data class Variant(
    val formFactor: String = FormFactor.PHONE,
    val abis: List<String> = emptyList(),
    val packageName: String,
    val versionName: String = "",
    val versionCode: Long = 0,
    val minSdk: Int = 1,
    val targetSdk: Int = 1,
    val tvCapable: Boolean = false,
    val label: String = "",
    val fileName: String = "",
    val url: String,
    val size: Long = 0,
    val sha256: String = "",
    /**
     * Found on the releases page by the app itself, ahead of the catalog: the file is the new
     * one, but the build details (versionCode, minSdk) are still those of the release before.
     */
    val live: Boolean = false,
)

object FormFactor {
    const val PHONE = "phone"
    const val TV = "tv"
    const val WATCH = "watch"
}

val CatalogJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Picks the text for the current language, falling back to English, then to anything. */
fun Map<String, String>?.localized(language: String): String {
    if (this.isNullOrEmpty()) return ""
    val key = if (language == "iw") "he" else language
    return this[key] ?: this["en"] ?: values.first()
}

fun CatalogApp.iconUrl(baseUrl: String): String? = icon?.let { if (it.startsWith("http")) it else baseUrl + it }

/** The localized name of a category: the registry first, then [fallback] (built-in names), then the id. */
fun List<CategoryDef>.nameOf(id: String, language: String, fallback: (String) -> String? = { null }): String {
    val fromRegistry = firstOrNull { it.id == id }?.name?.takeIf { it.isNotEmpty() }?.localized(language)
    return fromRegistry?.takeIf { it.isNotBlank() } ?: fallback(id) ?: id
}
