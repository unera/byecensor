package ru.uvw.byecensor

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * The app is drawn dark on purpose.
 *
 * Every screen sets its own dark surface and white text; the theme itself is
 * DayNight, so in a light-mode system the surfaces stayed light while the text
 * stayed white — white on white in the input dialogs. Rather than make ninety
 * hardcoded colours theme-aware, the app pins itself to the night variant: one
 * place instead of every screen.
 */
class ByecensorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
