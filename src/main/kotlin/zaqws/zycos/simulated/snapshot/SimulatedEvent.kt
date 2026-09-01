package zaqws.zycos.simulated.snapshot

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.math.SimulatedVector3
import java.util.concurrent.ArrayBlockingQueue

sealed interface SimulatedEvent {
    val tick: Long

    data class Spawn(
        override val tick: Long,
        val entityId: SimulatedEntityId
    ) : SimulatedEvent

    data class Remove(
        override val tick: Long,
        val entityId: SimulatedEntityId
    ) : SimulatedEvent

    data class Attack(
        override val tick: Long,
        val entityId: SimulatedEntityId,
        val targetEntityId: SimulatedEntityId?
    ) : SimulatedEvent

    data class Hurt(
        override val tick: Long,
        val entityId: SimulatedEntityId,
        val damage: Double
    ) : SimulatedEvent

    data class Death(
        override val tick: Long,
        val entityId: SimulatedEntityId
    ) : SimulatedEvent

    data class Knockback(
        override val tick: Long,
        val entityId: SimulatedEntityId,
        val velocity: SimulatedVector3
    ) : SimulatedEvent
}

internal class SimulatedEventQueue(maximumEvents: Int) {
    private val queue = ArrayBlockingQueue<SimulatedEvent>(maximumEvents)

    init {
        require(maximumEvents > 0)
    }

    fun offer(event: SimulatedEvent) {
        while (!queue.offer(event)) queue.poll()
    }

    fun drain(
        maximumEvents: Int = Int.MAX_VALUE,
        consumer: (SimulatedEvent) -> Unit
    ): Int {
        require(maximumEvents >= 0)

        var processedEvents = 0

        while (processedEvents < maximumEvents) {
            consumer(queue.poll() ?: break)
            processedEvents++
        }

        return processedEvents
    }

    fun drainTo(
        destination: MutableCollection<SimulatedEvent>,
        maximumEvents: Int = Int.MAX_VALUE
    ) = drain(maximumEvents) { destination.add(it) }

    fun clear() {
        queue.clear()
    }
}
