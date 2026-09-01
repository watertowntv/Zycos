package zaqws.zycos.simulated.external

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.math.SimulatedVector3

sealed interface SimulatedExternalAction {
    val actorId: SimulatedExternalActorId

    data class Damage(
        override val actorId: SimulatedExternalActorId,
        val amount: Double,
        val sourceEntityId: SimulatedEntityId? = null
    ) : SimulatedExternalAction {
        init {
            require(amount.isFinite())
            require(amount >= 0.0)
        }
    }

    data class Knockback(
        override val actorId: SimulatedExternalActorId,
        val velocity: SimulatedVector3,
        val sourceEntityId: SimulatedEntityId? = null
    ) : SimulatedExternalAction {
        init {
            require(velocity.isFinite)
        }
    }

    data class SetVelocity(
        override val actorId: SimulatedExternalActorId,
        val velocity: SimulatedVector3
    ) : SimulatedExternalAction {
        init {
            require(velocity.isFinite)
        }
    }
}