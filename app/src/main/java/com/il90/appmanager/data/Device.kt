package com.il90.appmanager.data

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/** What this device can run; decides which APK of an app is offered. */
data class Device(
    val formFactor: String,
    val abis: List<String>,
    val sdk: Int,
) {
    companion object {
        fun of(context: Context): Device {
            val pm = context.packageManager
            val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
            val form = when {
                pm.hasSystemFeature(PackageManager.FEATURE_WATCH) -> FormFactor.WATCH
                uiMode == Configuration.UI_MODE_TYPE_TELEVISION || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> FormFactor.TV
                else -> FormFactor.PHONE
            }
            return Device(form, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
        }
    }
}

sealed interface Compatibility {
    data class Ok(val variant: Variant) : Compatibility
    data object NoRelease : Compatibility
    /** The app exists only for other kinds of device (TV, watch). */
    data class OtherDevice(val formFactors: List<String>) : Compatibility
    data class NeedsNewerAndroid(val minSdk: Int) : Compatibility
    data object UnsupportedCpu : Compatibility
}

/** The best APK of [app] for [device], or why there is none. */
fun pickVariant(app: CatalogApp, device: Device): Compatibility {
    if (app.variants.isEmpty()) return Compatibility.NoRelease
    val sameForm = app.variants.filter {
        it.formFactor == device.formFactor || (device.formFactor == FormFactor.TV && it.formFactor == FormFactor.PHONE && it.tvCapable)
    }
    if (sameForm.isEmpty()) return Compatibility.OtherDevice(app.variants.map { it.formFactor }.distinct())
    val cpuOk = sameForm.filter { v -> v.abis.isEmpty() || v.abis.any { it in device.abis } }
    if (cpuOk.isEmpty()) return Compatibility.UnsupportedCpu
    val sdkOk = cpuOk.filter { it.minSdk <= device.sdk }
    if (sdkOk.isEmpty()) return Compatibility.NeedsNewerAndroid(cpuOk.minOf { it.minSdk })
    fun abiRank(v: Variant): Int =
        if (v.abis.isEmpty()) device.abis.size
        else v.abis.minOf { abi -> device.abis.indexOf(abi).let { if (it < 0) Int.MAX_VALUE else it } }
    // An exact form factor beats "phone build that also runs on TV"; then the device's preferred
    // CPU; then the smaller download (a single-ABI build over a universal one).
    val best = sdkOk.minWith(
        compareBy<Variant>({ if (it.formFactor == device.formFactor) 0 else 1 }, { abiRank(it) }, { it.size })
    )
    return Compatibility.Ok(best)
}

/** Android version name for an API level, e.g. 26 -> "8.0". */
fun androidVersionName(sdk: Int): String = when (sdk) {
    in Int.MIN_VALUE..20 -> "4.4"
    21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"
    28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"
    36 -> "16"; 37 -> "17"
    else -> (sdk - 20).toString()
}
