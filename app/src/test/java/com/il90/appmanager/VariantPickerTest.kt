package com.il90.appmanager

import com.il90.appmanager.data.CatalogApp
import com.il90.appmanager.data.Compatibility
import com.il90.appmanager.data.Device
import com.il90.appmanager.data.FormFactor
import com.il90.appmanager.data.Variant
import com.il90.appmanager.data.pickVariant
import com.il90.appmanager.ui.shareableVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantPickerTest {
    private val phone = Device(FormFactor.PHONE, listOf("arm64-v8a", "armeabi-v7a"), 36)

    private fun variant(
        form: String = FormFactor.PHONE,
        abis: List<String> = emptyList(),
        minSdk: Int = 26,
        size: Long = 10,
        name: String = "a.apk",
        tvCapable: Boolean = false,
    ) = Variant(formFactor = form, abis = abis, packageName = "p", minSdk = minSdk, size = size, fileName = name, url = "https://x/$name", tvCapable = tvCapable)

    private fun app(vararg variants: Variant) = CatalogApp(id = "a", name = "A", variants = variants.toList())

    @Test
    fun `a single-ABI build beats the universal one`() {
        val picked = pickVariant(
            app(
                variant(abis = listOf("arm64-v8a", "armeabi-v7a"), size = 42, name = "universal.apk"),
                variant(abis = listOf("armeabi-v7a"), size = 21, name = "v7.apk"),
                variant(abis = listOf("arm64-v8a"), size = 27, name = "arm64.apk"),
                variant(abis = listOf("x86_64"), size = 30, name = "x86.apk"),
            ), phone,
        )
        assertEquals("arm64.apk", (picked as Compatibility.Ok).variant.fileName)
    }

    @Test
    fun `the phone gets the phone build of a multi-device app`() {
        val picked = pickVariant(app(variant(FormFactor.TV, name = "tv.apk"), variant(FormFactor.WATCH, name = "watch.apk"), variant(name = "phone.apk")), phone)
        assertEquals("phone.apk", (picked as Compatibility.Ok).variant.fileName)
    }

    @Test
    fun `an app made only for TV is not offered on a phone`() {
        val picked = pickVariant(app(variant(FormFactor.TV)), phone)
        assertEquals(Compatibility.OtherDevice(listOf(FormFactor.TV)), picked)
    }

    @Test
    fun `a TV takes the TV build, or a phone build that supports TV`() {
        val tv = Device(FormFactor.TV, listOf("armeabi-v7a"), 34)
        val both = pickVariant(app(variant(name = "phone.apk", tvCapable = true), variant(FormFactor.TV, name = "tv.apk")), tv)
        assertEquals("tv.apk", (both as Compatibility.Ok).variant.fileName)
        val shared = pickVariant(app(variant(name = "phone.apk", tvCapable = true)), tv)
        assertEquals("phone.apk", (shared as Compatibility.Ok).variant.fileName)
        assertTrue(pickVariant(app(variant(name = "phone.apk")), tv) is Compatibility.OtherDevice)
    }

    @Test
    fun `too old Android and unsupported CPU are told apart`() {
        assertEquals(Compatibility.NeedsNewerAndroid(40), pickVariant(app(variant(minSdk = 40)), phone))
        assertEquals(Compatibility.UnsupportedCpu, pickVariant(app(variant(abis = listOf("x86_64"))), phone))
        assertEquals(Compatibility.NoRelease, pickVariant(app(), phone))
    }

    @Test
    fun `the APK sent to someone else is the phone build that runs on most phones`() {
        val universal = variant(name = "universal.apk", size = 30)
        val arm64 = variant(abis = listOf("arm64-v8a"), name = "arm64.apk")
        val x86 = variant(abis = listOf("x86_64"), name = "x86.apk")
        val tv = variant(form = FormFactor.TV, name = "tv.apk")
        assertEquals(universal, app(arm64, universal, x86, tv).shareableVariant())
        assertEquals(arm64, app(x86, arm64, tv).shareableVariant())
        // An app made only for TV still has a file to send.
        assertEquals(tv, app(tv).shareableVariant())
        assertNull(app().shareableVariant())
    }
}
