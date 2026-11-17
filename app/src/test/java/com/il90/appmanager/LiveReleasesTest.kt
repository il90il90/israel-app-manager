package com.il90.appmanager

import com.il90.appmanager.data.CatalogApp
import com.il90.appmanager.data.CatalogJson
import com.il90.appmanager.data.FormFactor
import com.il90.appmanager.data.GhRelease
import com.il90.appmanager.data.InstalledInfo
import com.il90.appmanager.data.LiveAsset
import com.il90.appmanager.data.LiveRelease
import com.il90.appmanager.data.Release
import com.il90.appmanager.data.Source
import com.il90.appmanager.data.Variant
import com.il90.appmanager.data.compareVersionNames
import com.il90.appmanager.data.isNewerThan
import com.il90.appmanager.data.isOlderThan
import com.il90.appmanager.data.liveVariants
import com.il90.appmanager.data.liveVersionName
import com.il90.appmanager.data.pickLatest
import com.il90.appmanager.data.toLive
import com.il90.appmanager.data.withLive
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list has to offer the newest release even while the catalog still describes the one before. */
class LiveReleasesTest {
    private fun variant(file: String, version: String, code: Long = 1, form: String = FormFactor.PHONE, abis: List<String> = emptyList()) =
        Variant(formFactor = form, abis = abis, packageName = "com.example.app", versionName = version, versionCode = code,
            minSdk = 26, fileName = file, url = "https://old/$file", size = 100, sha256 = "0".repeat(64))

    private fun asset(name: String, size: Long = 200) = LiveAsset(name, size, "https://new/$name", "a".repeat(64))

    // What api.github.com answered for OneDial 3.10.67 while the catalog still listed 3.10.57.
    private val oneDial = """
        {"tag_name": "v3.10.67", "name": "OneDial 3.10.67", "body": "Faster search.", "draft": false, "prerelease": false,
         "published_at": "2026-11-17T10:32:48Z",
         "html_url": "https://github.com/il90il90/OneDial-releases/releases/tag/v3.10.67",
         "assets": [{"name": "onedial-3.10.67.apk", "size": 48273665, "state": "uploaded",
                     "digest": "sha256:60c4443357aab3bae31b13fb828e77813c0f282709283fa989fefcae7b8072ba",
                     "browser_download_url": "https://github.com/il90il90/OneDial-releases/releases/download/v3.10.67/onedial-3.10.67.apk"}]}
    """.trimIndent()
    private val oneDialSource = Source("il90il90/OneDial-releases", tag = "^v\\d", asset = "^onedial-.*\\.apk$")
    private val oneDialInCatalog = CatalogApp(
        id = "onedial", name = "OneDial", source = oneDialSource,
        release = Release(tag = "v3.10.57", name = "OneDial 3.10.57", publishedAt = "2026-11-16T20:36:38Z"),
        variants = listOf(variant("onedial-3.10.57.apk", "3.10.57", code = 157, abis = listOf("arm64-v8a"))),
    )

    @Test
    fun `a release the catalog has not seen yet replaces the listed one`() {
        val live = CatalogJson.decodeFromString(GhRelease.serializer(), oneDial).toLive(oneDialSource)
        val shown = oneDialInCatalog.withLive(live)
        assertEquals("v3.10.67", shown.release?.tag)
        assertEquals("Faster search.", shown.release?.notes)
        val file = shown.variants.single()
        assertEquals("3.10.67", file.versionName)
        assertEquals("onedial-3.10.67.apk", file.fileName)
        assertEquals("https://github.com/il90il90/OneDial-releases/releases/download/v3.10.67/onedial-3.10.67.apk", file.url)
        assertEquals(48273665L, file.size)
        assertEquals("60c4443357aab3bae31b13fb828e77813c0f282709283fa989fefcae7b8072ba", file.sha256)
        assertTrue(file.live)
        // What the file name cannot say stays as the catalog knows it.
        assertEquals("com.example.app", file.packageName)
        assertEquals(listOf("arm64-v8a"), file.abis)
        assertEquals(26, file.minSdk)
    }

    @Test
    fun `the catalog's own release is left alone when it is the same or the newer one`() {
        val live = CatalogJson.decodeFromString(GhRelease.serializer(), oneDial).toLive(oneDialSource)!!
        val caughtUp = oneDialInCatalog.copy(release = Release(tag = "v3.10.67", publishedAt = "2026-11-17T10:32:48Z"))
        assertSame(caughtUp, caughtUp.withLive(live))
        val ahead = oneDialInCatalog.copy(release = Release(tag = "v3.10.68", publishedAt = "2026-11-17T11:00:00Z"))
        assertSame(ahead, ahead.withLive(live))
        assertSame(oneDialInCatalog, oneDialInCatalog.withLive(null))
    }

    @Test
    fun `drafts, prereleases, other products and other files are not offered`() {
        fun release(tag: String, vararg files: String, draft: Boolean = false, pre: Boolean = false) = """
            {"tag_name": "$tag", "draft": $draft, "prerelease": $pre, "published_at": "2026-11-17T10:00:00Z",
             "assets": [${files.joinToString(",") { """{"name": "$it", "size": ${it.length}, "browser_download_url": "https://x/$it"}""" }}]}
        """.let { CatalogJson.decodeFromString(GhRelease.serializer(), it) }
        val source = Source("o/r", tag = "^app-v\\d", asset = "\\.apk$")
        assertNull(release("app-v3", "a.apk", draft = true).toLive(source))
        assertNull(release("app-v3", "a.apk", pre = true).toLive(source))
        assertEquals("app-v3", release("app-v3", "a.apk", pre = true).toLive(source.copy(prerelease = true))?.tag)
        assertNull(release("v1.0.9", "bridge.apk").toLive(source))
        assertNull(release("app-v3", "notes.txt", "bundle.aab").toLive(source))
        assertEquals(listOf("a.APK"), release("app-v3", "a.APK", "a.apk.sha256").toLive(source)?.assets?.map { it.name })
        assertEquals(listOf("app-1.2.apk"), release("app-v3", "app-1.2.apk", "app-ads.apk").toLive(source.copy(exclude = "-ads"))?.assets?.map { it.name })
        // No digest on a file: it is still offered, only without a checksum to hold it to.
        assertEquals("", release("app-v3", "a.apk").toLive(source)?.assets?.single()?.sha256)
    }

    @Test
    fun `one file under two names is offered once, under the versioned name`() {
        val json = """
            {"tag_name": "v2.22.0", "published_at": "2026-11-17T10:00:00Z", "assets": [
              {"name": "Zoopli.apk", "size": 2381315, "browser_download_url": "https://x/Zoopli.apk"},
              {"name": "zoopli-2.22.0.apk", "size": 2381315, "browser_download_url": "https://x/zoopli-2.22.0.apk"},
              {"name": "SHA256SUMS.txt", "size": 150, "browser_download_url": "https://x/SHA256SUMS.txt"}]}
        """
        val live = CatalogJson.decodeFromString(GhRelease.serializer(), json).toLive(Source("o/r", asset = "\\.apk$"))
        assertEquals(listOf("zoopli-2.22.0.apk"), live?.assets?.map { it.name })
    }

    @Test
    fun `in a repository with several products the app's own newest release is found`() {
        val json = """
            [{"tag_name": "v1.0.9", "published_at": "2026-11-17T12:00:00Z", "assets": [{"name": "bridge.tar.gz", "size": 5, "browser_download_url": "https://x/b"}]},
             {"tag_name": "app-v23", "published_at": "2026-11-10T12:00:00Z", "assets": [{"name": "touchless-bridge-1.1.21.apk", "size": 5, "browser_download_url": "https://x/21"}]},
             {"tag_name": "app-v24", "published_at": "2026-11-16T08:40:55Z", "assets": [{"name": "touchless-bridge-1.1.22.apk", "size": 6, "browser_download_url": "https://x/22"}]}]
        """
        val releases = CatalogJson.decodeFromString(ListSerializer(GhRelease.serializer()), json)
        assertEquals("app-v24", pickLatest(releases, Source("o/r", tag = "^app-v\\d", asset = "\\.apk$"))?.tag)
        assertNull(pickLatest(releases, Source("o/r", tag = "^release-")))
    }

    @Test
    fun `the new version is read from the file name, or else from the tag`() {
        assertEquals("3.10.67", liveVersionName(variant("onedial-3.10.57.apk", "3.10.57"), "onedial-3.10.67.apk", "v3.10.67"))
        // The tag is a build counter; the file name carries the version.
        assertEquals("1.1.23", liveVersionName(variant("touchless-bridge-1.1.22.apk", "1.1.22"), "touchless-bridge-1.1.23.apk", "app-v25"))
        // The file name also carries a build number that changed: the tag decides.
        assertEquals("0.2.1074", liveVersionName(variant("SeretGram-0.2.1073-21073-arm64-v8a.apk", "0.2.1073"), "SeretGram-0.2.1074-21074-arm64-v8a.apk", "v0.2.1074"))
        assertEquals("1.0.2", liveVersionName(variant("WatchIPTV-phone-v1.0.1-26634011.apk", "1.0.1"), "WatchIPTV-phone-v1.0.2-26700000.apk", "v1.0.2-26700000"))
        // A file name with no version in it.
        assertEquals("1.5", liveVersionName(variant("israel-tv-tv.apk", "1.4"), "israel-tv-tv.apk", "v1.5"))
        assertEquals("1.8.1071", liveVersionName(variant("trustapk-client.apk", "1.8.1070"), "trustapk-client.apk", "v1.8.1071"))
        assertEquals("25", liveVersionName(variant("app.apk", "1.0"), "app.apk", "app-v25"))
    }

    @Test
    fun `each kind of file is matched with the same kind in the new release`() {
        val templates = listOf(
            variant("SeretGram-0.2.1073-21073-arm64-v8a.apk", "0.2.1073", abis = listOf("arm64-v8a")),
            variant("SeretGram-0.2.1073-21073-armeabi-v7a.apk", "0.2.1073", abis = listOf("armeabi-v7a")),
            variant("SeretGram-0.2.1073-21073-x86_64.apk", "0.2.1073", abis = listOf("x86_64")),
            variant("SeretGram-0.2.1073-21073.apk", "0.2.1073"),
        )
        val release = LiveRelease("v0.2.1074", assets = listOf(
            asset("SeretGram-0.2.1074-21074.apk", 4), asset("SeretGram-0.2.1074-21074-x86_64.apk", 3),
            asset("SeretGram-0.2.1074-21074-armeabi-v7a.apk", 2), asset("SeretGram-0.2.1074-21074-arm64-v8a.apk", 1),
        ))
        val fresh = liveVariants(templates, release)!!
        assertEquals(listOf(1L, 2L, 3L, 4L), fresh.map { it.size })
        assertEquals(templates.map { it.abis }, fresh.map { it.abis })
        assertTrue(fresh.all { it.versionName == "0.2.1074" && it.live })

        val screens = listOf(
            variant("WatchIPTV-phone-v1.0.1-26634011.apk", "1.0.1"),
            variant("WatchIPTV-tv-v1.0.1-26634011.apk", "1.0.1", form = FormFactor.TV),
            variant("WatchIPTV-watch-v1.0.1-26634011.apk", "1.0.1", form = FormFactor.WATCH),
        )
        val next = LiveRelease("v1.0.2-26700000", assets = listOf("watch", "phone", "tv").map { asset("WatchIPTV-$it-v1.0.2-26700000.apk") })
        assertEquals(
            listOf(FormFactor.PHONE to "WatchIPTV-phone-v1.0.2-26700000.apk", FormFactor.TV to "WatchIPTV-tv-v1.0.2-26700000.apk", FormFactor.WATCH to "WatchIPTV-watch-v1.0.2-26700000.apk"),
            liveVariants(screens, next)!!.map { it.formFactor to it.fileName },
        )
    }

    @Test
    fun `a release that does not line up with the known files is not half-applied`() {
        val two = listOf(variant("ScoreUp-phone-1.0.18.apk", "1.0.18"), variant("ScoreUp-watch-1.0.18.apk", "1.0.18", form = FormFactor.WATCH))
        // The watch build is missing from the new release.
        assertNull(liveVariants(two, LiveRelease("v1.0.19", assets = listOf(asset("ScoreUp-phone-1.0.19.apk")))))
        // Two files could both be the phone build.
        assertNull(liveVariants(two, LiveRelease("v1.0.19", assets = listOf(asset("ScoreUp-phone-1.0.19.apk"), asset("ScoreUp-phone-1.0.20.apk", 9), asset("ScoreUp-watch-1.0.19.apk")))))
        assertNull(liveVariants(emptyList(), LiveRelease("v1", assets = listOf(asset("a.apk")))))
        // One file before and one after is the same app, whatever it is called now.
        assertEquals("OneDial-release.apk", liveVariants(listOf(variant("onedial-3.10.57.apk", "3.10.57")), LiveRelease("v3.11.0", assets = listOf(asset("OneDial-release.apk"))))!!.single().fileName)
        // An extra file the catalog has no counterpart for does not get in the way.
        assertEquals(2, liveVariants(two, LiveRelease("v1.0.19", assets = listOf(asset("ScoreUp-phone-1.0.19.apk"), asset("ScoreUp-watch-1.0.19.apk"), asset("ScoreUp-tv-1.0.19.apk"))))!!.size)
    }

    @Test
    fun `version names are compared number by number`() {
        assertTrue(compareVersionNames("3.10.57", "3.10.67") < 0)
        assertTrue(compareVersionNames("3.10.9", "3.10.10") < 0)
        assertTrue(compareVersionNames("1.2", "1.1.9") > 0)
        assertEquals(0, compareVersionNames("0.7.3-qa", "0.7.3"))
        assertEquals(0, compareVersionNames("1.4", "1.4.0"))
        assertTrue(compareVersionNames("2.0", "") > 0)
    }

    @Test
    fun `a file found ahead of the catalog is judged by its version name`() {
        val listed = variant("onedial-3.10.57.apk", "3.10.57", code = 157)
        val found = listed.copy(versionName = "3.10.67", live = true)
        // The phone already has a build newer than the catalog's, older than the release.
        val between = InstalledInfo("3.10.60", 160, null)
        assertTrue(found.isNewerThan(between))
        assertFalse(listed.isNewerThan(between))
        assertFalse(found.isNewerThan(InstalledInfo("3.10.67", 167, null)))
        assertTrue(found.isOlderThan(InstalledInfo("3.10.68", 168, null)))
        assertFalse(found.isOlderThan(between))
    }
}
