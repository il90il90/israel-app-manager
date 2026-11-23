package com.il90.appmanager.data

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import com.il90.appmanager.BuildConfig

/** What this device can run; decides which APK of an app is offered. */
data class Device(
    val formFactor: String,
    val abis: List<String>,
    val sdk: Int,
    /** False on TVs and other devices driven by a remote, a keyboard or a mouse. */
    val touch: Boolean = true,
) {
    companion object {
        fun of(context: Context): Device {
            val pm = context.packageManager
            val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
            val form = when {
                pm.hasSystemFeature(PackageManager.FEATURE_WATCH) -> FormFactor.WATCH
                uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
                    pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY) ||
                    pm.hasSystemFeature("amazon.hardware.fire_tv") -> FormFactor.TV
                // Tablets, foldables and Chromebooks run phone builds.
                else -> FormFactor.PHONE
            }
            // Debug builds only: `adb shell settings put global iam_debug_form_factor tv` makes a
            // phone behave like a TV (remote focus, TV builds first), to test the TV screens.
            val forced = if (BuildConfig.DEBUG) {
                runCatching { Settings.Global.getString(context.contentResolver, "iam_debug_form_factor") }.getOrNull()
                    ?.takeIf { it == FormFactor.TV || it == FormFactor.PHONE || it == FormFactor.WATCH }
            } else null
            val actual = forced ?: form
            val touch = actual != FormFactor.TV && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            return Device(actual, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT, touch)
        }
    }
}

sealed interface Compatibility {
    /**
     * [variant] installs here. [foreign] when it was made for another kind of device (a TV
     * build on a phone, a phone build on a TV): it still installs and runs, but may expect a
     * touchscreen or a remote.
     */
    data class Ok(val variant: Variant, val foreign: Boolean = false) : Compatibility
    data object NoRelease : Compatibility
    /** The app exists only for a kind of device this one cannot stand in for (a watch). */
    data class OtherDevice(val formFactors: List<String>) : Compatibility
    data class NeedsNewerAndroid(val minSdk: Int) : Compatibility
    data object UnsupportedCpu : Compatibility
}

/**
 * The best APK of [app] for [device], or why there is none.
 *
 * Android installs any APK on any device, so a build made for another kind of device is
 * offered when this device has none of its own (or its own cannot run here): a TV gets the
 * phone build, a phone or tablet gets the TV build. Watch builds are the exception: they are
 * made for a round screen the size of a coin, so only a watch is offered one.
 */
fun pickVariant(app: CatalogApp, device: Device): Compatibility {
    if (app.variants.isEmpty()) return Compatibility.NoRelease
    fun native(v: Variant) =
        v.formFactor == device.formFactor || (device.formFactor == FormFactor.TV && v.formFactor == FormFactor.PHONE && v.tvCapable)
    val own = app.variants.filter(::native)
    val others = app.variants.filter { !native(it) && (device.formFactor == FormFactor.WATCH || it.formFactor != FormFactor.WATCH) }
    if (own.isEmpty() && others.isEmpty()) return Compatibility.OtherDevice(app.variants.map { it.formFactor }.distinct())
    val first = if (own.isNotEmpty()) pickFrom(own, device) else null
    if (first is Compatibility.Ok) return first
    val fallback = if (others.isNotEmpty()) pickFrom(others, device) else null
    if (fallback is Compatibility.Ok) return fallback.copy(foreign = true)
    // Nothing runs here: say why this device's own build does not.
    return first ?: fallback!!
}

private fun pickFrom(pool: List<Variant>, device: Device): Compatibility {
    val cpuOk = pool.filter { v -> v.abis.isEmpty() || v.abis.any { it in device.abis } }
    if (cpuOk.isEmpty()) return Compatibility.UnsupportedCpu
    val sdkOk = cpuOk.filter { it.minSdk <= device.sdk }
    if (sdkOk.isEmpty()) return Compatibility.NeedsNewerAndroid(cpuOk.minOf { it.minSdk })
    fun abiRank(v: Variant): Int =
        if (v.abis.isEmpty()) device.abis.size
        else v.abis.minOf { abi -> device.abis.indexOf(abi).let { if (it < 0) Int.MAX_VALUE else it } }
    // An exact form factor beats "phone build that also runs on TV" (and, among builds for other
    // devices, a phone build beats a TV one); then the device's preferred CPU; then the smaller
    // download (a single-ABI build over a universal one).
    val best = sdkOk.minWith(
        compareBy<Variant>({ formRank(it, device) }, { abiRank(it) }, { it.size })
    )
    return Compatibility.Ok(best)
}

private fun formRank(v: Variant, device: Device): Int = when (v.formFactor) {
    device.formFactor -> 0
    FormFactor.PHONE -> 1
    FormFactor.TV -> 2
    else -> 3
}

/** Android version name for an API level, e.g. 26 -> "8.0". */
fun androidVersionName(sdk: Int): String = when (sdk) {
    in Int.MIN_VALUE..20 -> "4.4"
    21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"
    28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"
    36 -> "16"; 37 -> "17"
    else -> (sdk - 20).toString()
}
