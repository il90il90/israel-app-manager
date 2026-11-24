package com.il90.appmanager

import com.il90.appmanager.data.CatalogCache
import com.il90.appmanager.data.CatalogRejected
import com.il90.appmanager.data.CatalogValidator
import com.il90.appmanager.data.CategoryDef
import com.il90.appmanager.data.catalogUrls
import com.il90.appmanager.data.nameOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CatalogValidatorTest {
    private val v2 = """
        {"schemaVersion": 2, "schema": 2, "baseUrl": "https://github.com/o/r/releases/download/catalog/",
         "categories": [{"id": "tools", "name": {"en": "Tools", "he": "כלים"}}, {"id": "other", "name": {"en": "Other"}}],
         "apps": [
           {"id": "good", "type": "link", "name": "Good", "category": "tools", "categories": ["tools", "other"],
            "icon": "icon-good-0123abcd.png", "screenshots": ["https://x.example/1.png", "http://x.example/2.png", "javascript:alert(1)"],
            "website": "javascript:alert(1)",
            "variants": [{"packageName": "p.good", "url": "https://x.example/good.apk", "sha256": "${"a".repeat(64)}"},
                         {"packageName": "p.good", "url": "http://x.example/insecure.apk"}]},
           {"id": "no-apk", "name": "Listed only", "variants": []},
           {"id": "good", "name": "Duplicate"},
           {"id": "Bad Id", "name": "Bad"},
           {"id": "noname", "name": ""}
         ]}
    """.trimIndent()

    @Test
    fun `bad apps and unsafe links are dropped, the rest stays`() {
        val catalog = CatalogValidator.parse(v2)
        assertEquals(2, catalog.version)
        assertEquals(listOf("good", "no-apk"), catalog.apps.map { it.id })
        val good = catalog.apps[0]
        assertEquals(listOf("https://x.example/good.apk"), good.variants.map { it.url })
        assertEquals(listOf("https://x.example/1.png"), good.screenshots)
        assertNull(good.website)
        assertEquals("icon-good-0123abcd.png", good.icon)
        assertEquals(listOf("tools", "other"), good.allCategories)
        // An app with nothing to install is still listed (the app shows it as not available).
        assertTrue(catalog.apps[1].variants.isEmpty())
        assertEquals(listOf("other"), catalog.apps[1].allCategories)
    }

    @Test
    fun `a newer schema is refused as incompatible`() {
        try {
            CatalogValidator.parse("""{"schemaVersion": 3, "apps": [{"id": "a", "name": "A"}]}""")
            fail("schema 3 must be refused")
        } catch (e: CatalogRejected) {
            assertTrue(e.incompatible)
        }
    }

    @Test
    fun `unreadable or empty catalogs are refused`() {
        for (text in listOf("", "not json", "[]", """{"schemaVersion": 2, "apps": []}""", """{"schemaVersion": 2, "apps": [{"id": "X", "name": "x"}]}""")) {
            try {
                CatalogValidator.parse(text)
                fail("must be refused: $text")
            } catch (e: CatalogRejected) {
                assertFalse(e.incompatible)
            }
        }
    }

    @Test
    fun `the legacy schema 1 catalog still loads`() {
        val catalog = CatalogValidator.parse("""{"schema": 1, "apps": [{"id": "a", "name": "A", "category": "kids"}]}""")
        assertEquals(1, catalog.version)
        assertEquals(listOf("kids"), catalog.apps[0].allCategories)
    }

    @Test
    fun `category names come from the registry, then built-in names, then the id`() {
        val registry = listOf(CategoryDef("tools", mapOf("en" to "Tools", "he" to "כלים")), CategoryDef("games", emptyMap()))
        assertEquals("כלים", registry.nameOf("tools", "he"))
        assertEquals("Tools", registry.nameOf("tools", "fr"))
        assertEquals("Built-in", registry.nameOf("games", "en") { "Built-in" })
        assertEquals("brand-new", registry.nameOf("brand-new", "en"))
    }

    @Test
    fun `the catalog release comes first, the branch mirror after`() {
        val urls = catalogUrls("o/r", "catalog", "catalog")
        assertEquals("https://github.com/o/r/releases/download/catalog/catalog.json", urls[0])
        assertTrue(urls[1].startsWith("https://raw.githubusercontent.com/o/r/refs/heads/catalog/"))
        assertEquals(3, urls.size)
    }
}

class CatalogCacheTest {
    private fun tempFile(): File = Files.createTempDirectory("catalog-cache").toFile().let { File(it, "catalog.json") }

    @Test
    fun `offline, the last good catalog is read back`() {
        val file = tempFile()
        val cache = CatalogCache(file)
        assertNull(cache.read())
        val text = """{"schemaVersion": 2, "apps": [{"id": "a", "name": "A"}]}"""
        CatalogValidator.parse(text)
        cache.write(text)
        val saved = cache.read()
        assertNotNull(saved)
        assertEquals("a", saved!!.apps.single().id)
        assertFalse(File(file.parentFile, "catalog.json.tmp").exists())
    }

    @Test
    fun `a damaged saved copy is ignored instead of crashing`() {
        val file = tempFile()
        file.writeText("{\"schemaVersion\": 2, \"apps\": [")
        assertNull(CatalogCache(file).read())
        file.writeText("""{"schemaVersion": 99, "apps": [{"id": "a", "name": "A"}]}""")
        assertNull(CatalogCache(file).read())
    }
}
