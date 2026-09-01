package zaqws.zycos.simulated.paper.interaction

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.external.SimulatedExternalActorId

sealed interface SimulatedInteraction {
    val actorId: SimulatedExternalActorId
    val targetEntityId: SimulatedEntityId

    data class Attack(
        override val actorId: SimulatedExternalActorId,
        override val targetEntityId: SimulatedEntityId,
        val damage: Double
    ) : SimulatedInteraction {
        init {
            require(damage.isFinite())
            require(damage >= 0.0)
        }
    }

    data class Interact(
        override val actorId: SimulatedExternalActorId,
        override val targetEntityId: SimulatedEntityId,
        val hand: Hand
    ) : SimulatedInteraction

    enum class Hand {
        MAIN,
        OFF
    }
}