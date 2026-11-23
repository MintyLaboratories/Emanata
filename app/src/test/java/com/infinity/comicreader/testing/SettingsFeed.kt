package com.infinity.comicreader.testing

import android.content.Context
import com.infinity.comicreader.data.SettingsRepository

/**
 * Stops [SettingsRepository] hearing stored settings change, as if its feed missed them: screens
 * see later changes only by checking the stored settings themselves. Lasts for this application.
 */
fun muteSettingsFeed(context: Context) {
    SettingsRepository.get(context).stopHearingSettings()
}
