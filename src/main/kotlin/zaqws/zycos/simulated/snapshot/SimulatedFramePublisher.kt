package zaqws.zycos.simulated.snapshot

import java.util.concurrent.atomic.AtomicReference

internal class SimulatedFramePublisher {
    private val reference = AtomicReference(SimulatedFrame.EMPTY)

    val latest: SimulatedFrame
        get() = reference.get()

    fun publish(frame: SimulatedFrame) {
        reference.set(frame)
    }

    fun clear() {
        reference.set(SimulatedFrame.EMPTY)
    }
}