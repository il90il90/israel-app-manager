package com.il90.appmanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserSettings(
    val autoCheck: Boolean = true,
    val deleteAfterInstall: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dismissedAnnouncement: String = "",
    val notificationsAsked: Boolean = false,
    /** The set of updates the last notification was about, so it is not repeated. */
    val notifiedUpdates: String = "",
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val autoCheck = booleanPreferencesKey("auto_check")
        val deleteAfterInstall = booleanPreferencesKey("delete_after_install")
        val theme = stringPreferencesKey("theme")
        val dismissedAnnouncement = stringPreferencesKey("dismissed_announcement")
        val notificationsAsked = booleanPreferencesKey("notifications_asked")
        val notifiedUpdates = stringPreferencesKey("notified_updates")
    }

    val flow: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            autoCheck = p[Keys.autoCheck] ?: true,
            deleteAfterInstall = p[Keys.deleteAfterInstall] ?: true,
            theme = runCatching { ThemeMode.valueOf(p[Keys.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            dismissedAnnouncement = p[Keys.dismissedAnnouncement].orEmpty(),
            notificationsAsked = p[Keys.notificationsAsked] ?: false,
            notifiedUpdates = p[Keys.notifiedUpdates].orEmpty(),
        )
    }

    suspend fun current(): UserSettings = flow.first()

    suspend fun setAutoCheck(value: Boolean) = context.dataStore.edit { it[Keys.autoCheck] = value }.let { }
    suspend fun setDeleteAfterInstall(value: Boolean) = context.dataStore.edit { it[Keys.deleteAfterInstall] = value }.let { }
    suspend fun setTheme(value: ThemeMode) = context.dataStore.edit { it[Keys.theme] = value.name }.let { }
    suspend fun dismissAnnouncement(id: String) = context.dataStore.edit { it[Keys.dismissedAnnouncement] = id }.let { }
    suspend fun setNotificationsAsked() = context.dataStore.edit { it[Keys.notificationsAsked] = true }.let { }
    suspend fun setNotifiedUpdates(value: String) = context.dataStore.edit { it[Keys.notifiedUpdates] = value }.let { }
}
