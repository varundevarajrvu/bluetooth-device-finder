package app.locate.core.ble

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.locate.core.time.Clock
import app.locate.service.ServiceStarter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Manifest-registered, so a disconnect still reaches Locate if Android killed its process.
 * The monitor dedupes against the runtime receiver in [app.locate.service.MonitorService].
 */
@AndroidEntryPoint
class AclReceiver : BroadcastReceiver() {
    @Inject lateinit var bus: BleEventBus
    @Inject lateinit var clock: Clock
    @Inject lateinit var serviceStarter: ServiceStarter

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED && intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val event = BluetoothBroadcasts.toEvent(intent, clock.now()) ?: return
        bus.send(event)
        serviceStarter.ensureRunning()
    }
}
