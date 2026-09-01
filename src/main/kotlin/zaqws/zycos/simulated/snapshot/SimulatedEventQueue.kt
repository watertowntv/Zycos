package zaqws.zycos.simulated.snapshot

import java.util.concurrent.ArrayBlockingQueue

internal class SimulatedEventQueue(
    maximumEvents: Int
) {
    private val queue =
        ArrayBlockingQueue<SimulatedEvent>(
            maximumEvents
        )

    init {
        require(maximumEvents > 0)
    }

    val isEmpty: Boolean
        get() = queue.isEmpty()

    fun offer(event: SimulatedEvent) {
        while (!queue.offer(event)) {
            queue.poll()
        }
    }

    fun drain(
        maximumEvents: Int = Int.MAX_VALUE,
        consumer: (SimulatedEvent) -> Unit
    ): Int {
        require(maximumEvents >= 0)

        var processedEvents = 0

        while (processedEvents < maximumEvents) {
            val event = queue.poll() ?: break

            consumer(event)
            processedEvents++
        }

        return processedEvents
    }

    fun drainTo(
        destination: MutableCollection<SimulatedEvent>,
        maximumEvents: Int = Int.MAX_VALUE
    ): Int {
        require(maximumEvents >= 0)

        return drain(maximumEvents) {
            destination.add(it)
        }
    }

    fun clear() {
        queue.clear()
    }
}
