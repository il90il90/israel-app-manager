package com.il90.appmanager

import com.il90.appmanager.data.Catalog
import com.il90.appmanager.data.CatalogJson
import com.il90.appmanager.data.iconUrl
import com.il90.appmanager.data.localized
import com.il90.appmanager.ui.components.MdBlock
import com.il90.appmanager.ui.components.parseMarkdown
import com.il90.appmanager.ui.components.telegramHandle
import com.il90.appmanager.ui.details.trimRedundantHeading
import com.il90.appmanager.ui.shareLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAndMarkdownTest {
    @Test
    fun `a catalog with fields this version does not know still loads`() {
        val json = """
            {"schema": 9, "baseUrl": "https://example.org/c/", "somethingNew": {"a": 1},
             "apps": [{"id": "x", "name": "X", "tagline": {"en": "Hello", "he": "שלום"}, "icon": "icons/x.png?v=1",
                       "future": true, "release": {"tag": "v1", "notes": "n", "url": null},
                       "variants": [{"packageName": "p.x", "url": "https://example.org/x.apk", "versionCode": 7, "extra": []}]},
                      {"id": "y", "name": "Y", "release": null, "variants": []}]}
        """.trimIndent()
        val catalog = CatalogJson.decodeFromString(Catalog.serializer(), json)
        assertEquals(2, catalog.apps.size)
        val x = catalog.apps[0]
        assertEquals("שלום", x.tagline.localized("iw"))
        assertEquals("Hello", x.tagline.localized("fr"))
        assertEquals("https://example.org/c/icons/x.png?v=1", x.iconUrl(catalog.baseUrl))
        assertEquals(7L, x.variants.single().versionCode)
        assertNull(catalog.apps[1].release)
        assertEquals("", catalog.apps[1].tagline.localized("en"))
    }

    @Test
    fun `premium apps carry their license and where to ask for one`() {
        val json = """
            {"contact": {"telegram": "https://t.me/someone"},
             "apps": [{"id": "p", "name": "P", "license": {"required": true, "contact": "https://t.me/someone/"},
                       "latestUrl": "https://github.com/o/r/releases/latest/download/P.apk",
                       "release": {"tag": "v1", "url": "https://github.com/o/r/releases/tag/v1"}},
                      {"id": "f", "name": "F", "license": null, "website": "https://f.example",
                       "release": {"tag": "v2", "url": "https://github.com/o/f/releases/tag/v2"}},
                      {"id": "n", "name": "N", "repoUrl": "https://github.com/o/n"}]}
        """.trimIndent()
        val catalog = CatalogJson.decodeFromString(Catalog.serializer(), json)
        assertEquals("https://t.me/someone", catalog.contact?.telegram)
        val (premium, free, bare) = catalog.apps
        assertTrue(premium.needsLicense)
        assertEquals("@someone", telegramHandle(premium.license?.contact.orEmpty()))
        assertFalse(free.needsLicense)
        // The link handed to other people: always-latest first, then this release, then whatever there is.
        assertEquals("https://github.com/o/r/releases/latest/download/P.apk", premium.shareLink())
        assertEquals("https://github.com/o/f/releases/tag/v2", free.shareLink())
        assertEquals("https://github.com/o/n", bare.shareLink())
    }

    @Test
    fun `release notes become headings, bullets and paragraphs`() {
        val blocks = parseMarkdown(
            """
            ## What's new
            <!-- commit=abc -->
            First line
            continues here.

            - **Bold** bullet
              wrapped onto a second line
            * another
            | file | what |
            |---|---|
            | `a.apk` | phone |
            """.trimIndent()
        )
        assertEquals(MdBlock.Heading("What's new"), blocks[0])
        assertEquals(MdBlock.Paragraph("First line continues here."), blocks[1])
        assertEquals(MdBlock.Bullet("**Bold** bullet wrapped onto a second line", 0), blocks[2])
        assertEquals(MdBlock.Bullet("another", 0), blocks[3])
        assertEquals(MdBlock.Bullet("file  ·  what", 0), blocks[4])
        assertEquals(MdBlock.Bullet("`a.apk`  ·  phone", 0), blocks[5])
        assertEquals(6, blocks.size)
        assertTrue(parseMarkdown("<!-- only a comment -->").isEmpty())
    }
}

class ReleaseNotesHeadingTest {
    private val redundant = listOf("What's new", "3.3.0", "v3.3.0", "Gallery Wall")

    @Test
    fun `a heading that repeats the card is dropped`() {
        val trim = ::trimRedundantHeading
        assertEquals("Body", trim("## What's new\n\nBody", redundant))
        assertEquals("- a", trim("# v3.3.0\n- a", redundant))
        assertEquals("text", trim("## Gallery Wall 3.3.0\ntext", redundant))
        assertEquals("## Sharing\ntext", trim("## Sharing\ntext", redundant))
        assertEquals("plain first line\nmore", trim("<!-- x -->plain first line\nmore", redundant))
    }
}
