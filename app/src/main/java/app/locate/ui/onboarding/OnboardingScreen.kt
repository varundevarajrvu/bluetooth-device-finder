package app.locate.ui.onboarding

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.locate.core.permissions.PermissionSpec
import app.locate.core.permissions.PermissionStep
import app.locate.core.permissions.PermissionStepId
import app.locate.ui.common.LOCATION_DISCLAIMER
import app.locate.ui.common.openAppSettings
import app.locate.ui.common.safeStart

@Composable
fun OnboardingScreen(onFinished: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val page by vm.page.collectAsStateWithLifecycle()
    LaunchedEffect(page) { if (page == null) onFinished() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(vm.progress, style = MaterialTheme.typography.labelMedium)
            when (val p = page) {
                OnboardingPage.Intro -> Intro(vm::next)
                is OnboardingPage.Permission -> PermissionPage(p.step, vm, vm::next)
                OnboardingPage.Battery -> BatteryPage(vm, vm::next)
                OnboardingPage.Limits -> LimitsPage(vm::next)
                null -> Unit
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Intro(onNext: () -> Unit) {
    Title("Don't leave your earbuds behind")
    Body(
        "Locate watches the Bluetooth devices you add: earbuds, speakers, watches. If one disconnects " +
            "while you're walking away from it, you get an alert: \"You may have left your earbuds behind.\"",
    )
    Body("First, a few permissions. Each one comes with a reason, and you can say no.")
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

private data class Rationale(val title: String, val body: String)

private fun rationale(id: PermissionStepId, sdk: Int) = when (id) {
    PermissionStepId.BLUETOOTH -> Rationale(
        "Nearby devices",
        "Locate needs to see when your devices connect and disconnect, and how strong their signal is. " +
            "It only watches devices you've paired and added.",
    )
    PermissionStepId.LOCATION -> Rationale(
        "Precise location",
        "When a device disconnects, Locate saves where your phone was at that moment. That's the only " +
            "location a Bluetooth device can have. Choose \"Precise\": approximate location is too coarse for alerts. " +
            "Your locations stay on this phone.",
    )
    PermissionStepId.BACKGROUND_LOCATION -> Rationale(
        "Location all the time",
        "Devices usually disconnect while your phone is in your pocket. To check whether you're walking away, " +
            "Locate needs location while it's in the background." +
            if (PermissionSpec.backgroundLocationViaSettings(sdk)) " On the next screen, choose \"Allow all the time\"." else "",
    )
    PermissionStepId.NOTIFICATIONS -> Rationale(
        "Notifications",
        "This is how the alert reaches you. Locate also shows a quiet, permanent notification while it's watching: " +
            "Android requires this for apps that run in the background.",
    )
}

@Composable
private fun PermissionPage(step: PermissionStep, vm: OnboardingViewModel, onNext: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var deniedForGood by remember(step) { mutableStateOf(false) }
    var attempts by remember(step) { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        attempts++
        if (vm.permissions.isGranted(step)) {
            onNext()
        } else {
            // After a denial, no rationale means "don't ask again": only Settings can grant it now.
            deniedForGood = activity != null &&
                step.permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (attempts > 0 && vm.permissions.isGranted(step)) onNext()
    }

    val r = rationale(step.id, vm.sdk)
    Title(r.title)
    Body(r.body)
    if (attempts > 0 && !deniedForGood) Body("Without this, ${consequence(step.id)}")
    if (deniedForGood) {
        Body("Android won't ask again. You can turn it on in Settings → Permissions. Without it, ${consequence(step.id)}")
        Button(onClick = { context.openAppSettings() }, modifier = Modifier.fillMaxWidth()) { Text("Open settings") }
    } else {
        Button(onClick = { launcher.launch(step.permissions.toTypedArray()) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (attempts > 0) "Try again" else "Allow")
        }
    }
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
}

private fun consequence(id: PermissionStepId) = when (id) {
    PermissionStepId.BLUETOOTH -> "Locate can't see your devices at all."
    PermissionStepId.LOCATION -> "Locate can't tell whether you've walked away, so there are no alerts."
    PermissionStepId.BACKGROUND_LOCATION -> "alerts stop working after a restart, or when Android moves Locate to the background."
    PermissionStepId.NOTIFICATIONS -> "you won't see any alerts."
}

@SuppressLint("BatteryLife") // Allowed for companion-device apps; see the note in AndroidManifest.xml.
@Composable
private fun BatteryPage(vm: OnboardingViewModel, onNext: () -> Unit) {
    val context = LocalContext.current
    var ignored by remember { mutableStateOf(vm.permissions.isBatteryOptimizationIgnored()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ignored = vm.permissions.isBatteryOptimizationIgnored() }

    Title("Let Locate run in the background")
    Body(
        "Android and phone makers stop background apps to save battery. If Locate is stopped, alerts stop. " +
            "Locate scans briefly every few minutes and only uses GPS right after a device disconnects.",
    )
    if (ignored) {
        Body("Done: battery optimisation is off for Locate.")
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    } else {
        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                if (!context.safeStart(intent)) context.safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Allow") }
        TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
    Spacer(Modifier.height(8.dp))
    Body("On Xiaomi, Vivo, Oppo, Realme, OnePlus and Samsung phones, also allow autostart and unrestricted battery in the app's settings.")
    OutlinedButton(onClick = { context.openAppSettings() }, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
}

@Composable
private fun LimitsPage(onNext: () -> Unit) {
    Title("What Locate can and can't do")
    Body("• $LOCATION_DISCLAIMER")
    Body(
        "• Alerts say \"may have\" because Locate can't tell earbuds put away in their case from earbuds left on " +
            "a table. If it's with you, tap \"It's with me\".",
    )
    Body("• Add safe zones, like home and work, so leaving a speaker at home doesn't trigger an alert.")
    Body("• Only devices you've paired with this phone can be added. Locate can't be used to follow anyone else's devices.")
    Body("• Everything stays on this phone.")
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}
