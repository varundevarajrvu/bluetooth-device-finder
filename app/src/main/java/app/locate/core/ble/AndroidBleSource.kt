package app.locate.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.util.size
import app.locate.data.db.BtType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Every Bluetooth call is guarded by [hasPermissions]; lint can't see through the guard. */
@SuppressLint("MissingPermission")
class AndroidBleSource(
    private val context: Context,
    bus: BleEventBus,
) : BleSource {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    override val isFake = false
    override val events: Flow<BleEvent> = bus.events

    override fun isSupported() = adapter != null
    override fun isEnabled() = adapter?.isEnabled == true

    override fun bondedDevices(): List<BtDeviceInfo> {
        if (!hasPermissions()) return emptyList()
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded.map { BtDeviceInfo(it.address, it.name, it.btType()) }.sortedBy { it.name ?: it.address }
    }

    override suspend fun connectedAddresses(): Set<String> {
        if (!hasPermissions() || !isEnabled()) return emptySet()
        val viaProfiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).flatMap { connectedVia(it) }
        val viaGatt = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList())
        return (viaProfiles + viaGatt).map { it.address }.toSet()
    }

    private suspend fun connectedVia(profile: Int): List<BluetoothDevice> = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    val devices = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                    adapter?.closeProfileProxy(p, proxy)
                    if (cont.isActive) cont.resume(devices)
                }

                override fun onServiceDisconnected(p: Int) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
            if (adapter?.getProfileProxy(context, listener, profile) != true && cont.isActive) cont.resume(emptyList())
        }
    } ?: emptyList()

    override fun scan(lowLatency: Boolean, addresses: Set<String>?): Flow<ScanSighting> {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null || !hasPermissions()) return emptyFlow()
        return callbackFlow {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    trySend(result.toSighting())
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { trySend(it.toSighting()) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE scan failed: $errorCode")
                    close()
                }
            }
            val settings = ScanSettings.Builder()
                .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            try {
                val filters = addresses?.map { ScanFilter.Builder().setDeviceAddress(it).build() } ?: emptyList()
                scanner.startScan(filters, settings, callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to scan", e)
                close()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Bad scan filter", e)
                close()
            }
            awaitClose { runCatching { scanner.stopScan(callback) } }
        }
    }

    private fun ScanResult.toSighting(): ScanSighting {
        val record = scanRecord
        val manufacturer = record?.manufacturerSpecificData
        return ScanSighting(
            address = device.address,
            name = record?.deviceName ?: runCatching { device.name }.getOrNull(),
            rssi = rssi,
            timestamp = System.currentTimeMillis(),
            bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
            manufacturerIds = manufacturer?.let { m -> (0 until m.size).map { m.keyAt(it) } } ?: emptyList(),
            serviceUuids = record?.serviceUuids?.map { it.toString() } ?: emptyList(),
        )
    }

    private fun BluetoothDevice.btType() = when (runCatching { type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> BtType.CLASSIC
        BluetoothDevice.DEVICE_TYPE_LE -> BtType.LE
        BluetoothDevice.DEVICE_TYPE_DUAL -> BtType.DUAL
        else -> BtType.UNKNOWN
    }

    private fun hasPermissions(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 31) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private companion object {
        const val TAG = "Locate"
        const val PROXY_TIMEOUT_MS = 2_000L
    }
}
