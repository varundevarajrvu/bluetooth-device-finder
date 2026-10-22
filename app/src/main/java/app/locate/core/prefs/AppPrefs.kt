package app.locate.core.prefs

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Small synchronous flags that receivers need to read without touching the database. */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("locate", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING, value) }

    /** The user's on/off switch for background monitoring. */
    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING, false)
        set(value) = prefs.edit { putBoolean(KEY_MONITORING, value) }

    private companion object {
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_MONITORING = "monitoring_enabled"
    }
}
