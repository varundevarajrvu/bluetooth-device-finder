package app.locate.core.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.IntentCompat

/** Maps Bluetooth system broadcasts to [BleEvent]s. Shared by the manifest and runtime receivers. */
object BluetoothBroadcasts {
    val runtimeFilter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
    }

    fun toEvent(intent: Intent, now: Long): BleEvent? = when (intent.action) {
        BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.let {
                BleEvent.Connection(it.address, intent.action == BluetoothDevice.ACTION_ACL_CONNECTED, now)
            }
        }
        BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> BleEvent.AdapterTurningOff(now)
            BluetoothAdapter.STATE_ON -> BleEvent.AdapterOn(now)
            else -> null
        }
        else -> null
    }
}
