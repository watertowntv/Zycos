package zaqws.zycos.simulated.snapshot

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.math.SimulatedVector3

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

    data class Jump(
        override val tick: Long,
        val entityId: SimulatedEntityId
    ) : SimulatedEvent

    data class Knockback(
        override val tick: Long,
        val entityId: SimulatedEntityId,
        val velocity: SimulatedVector3
    ) : SimulatedEvent
}