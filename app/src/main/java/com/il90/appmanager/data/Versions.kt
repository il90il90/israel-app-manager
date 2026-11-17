package com.il90.appmanager.data

private val NUMBER = Regex("\\d+")

private fun numbers(version: String): List<Long> =
    NUMBER.findAll(version).map { it.value.take(18).toLongOrNull() ?: 0L }.toList()

/** Orders "3.10.57" before "3.10.67": number by number, whatever stands between them. */
fun compareVersionNames(a: String, b: String): Int {
    val x = numbers(a)
    val y = numbers(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0L }.compareTo(y.getOrElse(i) { 0L })
        if (d != 0) return d
    }
    return 0
}

/**
 * Is this file a newer build than the installed one? The versionCode decides, except for a file
 * found ahead of the catalog, whose versionCode is not known yet: there the names are compared.
 */
fun Variant.isNewerThan(have: InstalledInfo): Boolean =
    if (live) compareVersionNames(versionName, have.versionName) > 0 else versionCode > have.versionCode

fun Variant.isOlderThan(have: InstalledInfo): Boolean =
    if (live) compareVersionNames(versionName, have.versionName) < 0 else versionCode < have.versionCode
