package app.locate.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.locate.core.ble.BleSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ble: BleSource,
) {
    fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isGranted(step: PermissionStep) = step.permissions.all(::isGranted) ||
        // The location step counts as granted with FINE alone; COARSE is only there for the API 31+ dialog.
        (step.id == PermissionStepId.LOCATION && isGranted(Manifest.permission.ACCESS_FINE_LOCATION))

    fun hasBluetoothPermissions() = Build.VERSION.SDK_INT < 31 ||
        (isGranted(Manifest.permission.BLUETOOTH_SCAN) && isGranted(Manifest.permission.BLUETOOTH_CONNECT))

    fun hasFineLocation() = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation() = Build.VERSION.SDK_INT < 29 || isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun isBatteryOptimizationIgnored(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun snapshot(): PermissionSnapshot {
        val sdk = Build.VERSION.SDK_INT
        val granted = PermissionSpec.steps(sdk).filter(::isGranted).map { it.id }.toSet()
        return PermissionSnapshot(
            sdk = sdk,
            granted = granted,
            preciseLocation = hasFineLocation(),
            batteryOptimizationIgnored = isBatteryOptimizationIgnored(),
            bluetoothEnabled = ble.isEnabled(),
        )
    }
}
