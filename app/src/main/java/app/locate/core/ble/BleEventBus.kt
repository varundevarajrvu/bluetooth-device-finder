package app.locate.core.ble

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands broadcast-receiver events to the monitor. A buffered channel rather than a SharedFlow,
 * so an event that wakes a dead process is held until the monitor starts collecting.
 */
@Singleton
class BleEventBus @Inject constructor() {
    private val channel = Channel<BleEvent>(Channel.UNLIMITED)
    val events: Flow<BleEvent> = channel.receiveAsFlow()

    fun send(event: BleEvent) {
        channel.trySend(event)
    }
}
