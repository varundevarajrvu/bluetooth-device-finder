package app.locate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.locate.core.prefs.AppPrefs
import app.locate.service.ServiceStarter
import app.locate.ui.nav.LocateNavHost
import app.locate.ui.theme.LocateTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var serviceStarter: ServiceStarter

    /** Device to open, from a tapped alert notification. Consumed by the nav host. */
    private val openDevice = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        setContent {
            LocateTheme {
                LocateNavHost(
                    startOnboarding = !prefs.onboardingDone,
                    openDeviceId = openDevice.value,
                    onDeviceOpened = { openDevice.value = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Starting from the foreground is always allowed, so this heals a monitor an OEM killed.
        serviceStarter.ensureRunning()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_DEVICE_ID, -1) ?: -1
        if (id >= 0) openDevice.value = id
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}
