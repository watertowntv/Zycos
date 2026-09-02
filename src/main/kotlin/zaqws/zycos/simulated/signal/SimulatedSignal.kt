@file:Suppress("unused")

package zaqws.zycos.simulated.signal

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.math.SimulatedVector3
import java.util.concurrent.ArrayBlockingQueue

interface SimulatedSignal

data class SimulatedSignalEvent(
    val tick: Long,
    val entityId: SimulatedEntityId,
    val position: SimulatedVector3,
    val signal: SimulatedSignal
)

internal class SimulatedSignalQueue(maximumSignals: Int) {
    private val queue =
        ArrayBlockingQueue<SimulatedSignalEvent>(
            maximumSignals
        )

    init {
        require(maximumSignals > 0)
    }

    fun offer(signal: SimulatedSignalEvent) {
        while (!queue.offer(signal)) queue.poll()
    }

    fun drainTo(
        destination: MutableCollection<SimulatedSignalEvent>,
        maximumSignals: Int
    ): Int {
        require(maximumSignals >= 0)

        var drainedSignals = 0

        while (drainedSignals < maximumSignals) {
            destination.add(queue.poll() ?: break)
            drainedSignals++
        }

        return drainedSignals
    }

    fun clear() {
        queue.clear()
    }
}
