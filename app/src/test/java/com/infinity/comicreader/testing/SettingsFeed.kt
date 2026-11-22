package com.infinity.comicreader.testing

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.infinity.comicreader.data.SettingsRepository

/**
 * Stops [SettingsRepository] hearing stored settings change, as if its feed missed them: screens
 * see later changes only by checking the stored settings themselves. Lasts for this application.
 */
fun muteSettingsFeed(context: Context) {
    val repository = SettingsRepository.get(context)
    val listener = SettingsRepository::class.java.getDeclaredField("listener").apply { isAccessible = true }
        .get(repository) as SharedPreferences.OnSharedPreferenceChangeListener
    PreferenceManager.getDefaultSharedPreferences(context.applicationContext).unregisterOnSharedPreferenceChangeListener(listener)
}
