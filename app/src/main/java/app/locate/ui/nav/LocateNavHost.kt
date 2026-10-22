package app.locate.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.locate.ui.alertlog.AlertLogScreen
import app.locate.ui.devices.DeviceDetailScreen
import app.locate.ui.devices.DeviceListScreen
import app.locate.ui.devices.RegisterScreen
import app.locate.ui.onboarding.OnboardingScreen
import app.locate.ui.zones.SafeZonesScreen

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val REGISTER = "register"
    const val DEVICE = "device/{id}"
    const val ZONES = "zones"
    const val ALERT_LOG = "alerts"
    fun device(id: Long) = "device/$id"
}

@Composable
fun LocateNavHost(startOnboarding: Boolean, openDeviceId: Long?, onDeviceOpened: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(openDeviceId) {
        if (openDeviceId != null && !startOnboarding) {
            nav.navigate(Routes.device(openDeviceId)) { launchSingleTop = true }
            onDeviceOpened()
        }
    }

    NavHost(navController = nav, startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.HOME) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onFinished = {
                if (nav.previousBackStackEntry != null) {
                    nav.popBackStack()
                } else {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
            })
        }
        composable(Routes.HOME) {
            DeviceListScreen(
                onAddDevice = { nav.navigate(Routes.REGISTER) },
                onOpenDevice = { nav.navigate(Routes.device(it)) },
                onOpenZones = { nav.navigate(Routes.ZONES) },
                onOpenAlertLog = { nav.navigate(Routes.ALERT_LOG) },
                onFixPermissions = { nav.navigate(Routes.ONBOARDING) },
            )
        }
        composable(Routes.REGISTER) { RegisterScreen(onDone = { nav.popBackStack() }) }
        composable(Routes.DEVICE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            DeviceDetailScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ZONES) { SafeZonesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ALERT_LOG) { AlertLogScreen(onBack = { nav.popBackStack() }) }
    }
}
