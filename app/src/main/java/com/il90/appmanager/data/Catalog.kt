package com.il90.appmanager.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The remote catalog, as written by tools/catalog/build_catalog.py. */
@Serializable
data class Catalog(
    val schema: Int = 1,
    val generatedAt: String? = null,
    val baseUrl: String = "",
    val announcement: Announcement? = null,
    val apps: List<CatalogApp> = emptyList(),
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
    val category: String = "tools",
    val developer: String = "",
    val website: String? = null,
    val repoUrl: String? = null,
    val featured: Boolean = false,
    val self: Boolean = false,
    val order: Int = 0,
    val icon: String? = null,
    val iconStyle: String? = null,
    val accent: String? = null,
    val release: Release? = null,
    val variants: List<Variant> = emptyList(),
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
