package zaqws.zycos.simulated.goal

import zaqws.zycos.simulated.SimulatedTarget
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.projectile.SimulatedProjectileDefinition

sealed interface SimulatedIntent {
    val priority: Int

    data class MoveTo(
        val position: SimulatedVector3,
        val stoppingDistance: Double = 0.0,
        override val priority: Int = DEFAULT_PRIORITY
    ) : SimulatedIntent {
        init {
            require(position.isFinite)
            require(stoppingDistance.isFinite())
            require(stoppingDistance >= 0.0)
        }
    }

    data class MoveAwayFrom(
        val position: SimulatedVector3,
        val distance: Double,
        override val priority: Int = DEFAULT_PRIORITY
    ) : SimulatedIntent {
        init {
            require(position.isFinite)
            require(distance.isFinite())
            require(distance > 0.0)
        }
    }

    data class LookAt(
        val position: SimulatedVector3,
        override val priority: Int = DEFAULT_PRIORITY
    ) : SimulatedIntent {
        init {
            require(position.isFinite)
        }
    }

    data class Attack(
        val target: SimulatedTarget,
        override val priority: Int = DEFAULT_PRIORITY
    ) : SimulatedIntent

    data class Shoot(
        val target: SimulatedTarget,
        val projectileDefinition: SimulatedProjectileDefinition,
        val projectileSpeed: Double,
        val maximumDistance: Double,
        override val priority: Int = DEFAULT_PRIORITY
    ) : SimulatedIntent {
        init {
            require(projectileSpeed.isFinite())
            require(projectileSpeed > 0.0)
            require(maximumDistance.isFinite())
            require(maximumDistance > 0.0)
        }
    }

    data class SetTarget(
        val target: SimulatedTarget?,
        override val priority: Int = DEFAULT_PRIORITY
    ) : SimulatedIntent

    data class StopMovement(
        override val priority: Int = DEFAULT_PRIORITY
    ) : SimulatedIntent

    companion object {
        const val DEFAULT_PRIORITY = 0
    }
}
