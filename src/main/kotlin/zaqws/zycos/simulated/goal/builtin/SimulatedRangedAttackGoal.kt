@file:Suppress("unused")

package zaqws.zycos.simulated.goal.builtin

import zaqws.zycos.simulated.goal.SimulatedGoal
import zaqws.zycos.simulated.goal.SimulatedGoalContext
import zaqws.zycos.simulated.goal.SimulatedGoalRuntime
import zaqws.zycos.simulated.goal.SimulatedIntent
import zaqws.zycos.simulated.projectile.SimulatedProjectileDefinition

class SimulatedRangedAttackGoal(
    val projectileDefinition: SimulatedProjectileDefinition,
    val projectileSpeed: Double,
    val maximumDistance: Double =
        projectileDefinition.maximumRange,
    val movementPriority: Int = DEFAULT_MOVEMENT_PRIORITY,
    val attackPriority: Int = DEFAULT_ATTACK_PRIORITY,
    val lookPriority: Int = DEFAULT_LOOK_PRIORITY
) : SimulatedGoal {
    init {
        require(projectileSpeed.isFinite())
        require(projectileSpeed > 0.0)
        require(maximumDistance.isFinite())
        require(maximumDistance > 0.0)
        require(
            maximumDistance <=
                    projectileDefinition.maximumRange
        )
    }

    override fun evaluate(
        context: SimulatedGoalContext,
        runtime: SimulatedGoalRuntime,
        intents: MutableCollection<SimulatedIntent>
    ) {
        val target =
            context.currentTarget()
                ?: return

        if (!target.isAlive) {
            return
        }

        intents.add(
            SimulatedIntent.LookAt(
                target.position,
                lookPriority
            )
        )

        val distanceSquared =
            context.position
                .distanceSquared(
                    target.position
                )

        if (
            distanceSquared >
            maximumDistance * maximumDistance
        ) {
            intents.add(
                SimulatedIntent.MoveTo(
                    position = target.position,
                    stoppingDistance =
                        maximumDistance,
                    priority =
                        movementPriority
                )
            )

            return
        }

        intents.add(
            SimulatedIntent.StopMovement(
                movementPriority
            )
        )

        intents.add(
            SimulatedIntent.Shoot(
                target = target.target,
                projectileDefinition =
                    projectileDefinition,
                projectileSpeed =
                    projectileSpeed,
                maximumDistance =
                    maximumDistance,
                priority =
                    attackPriority
            )
        )
    }

    companion object {
        const val DEFAULT_MOVEMENT_PRIORITY = 10
        const val DEFAULT_ATTACK_PRIORITY = 10
        const val DEFAULT_LOOK_PRIORITY = 10
    }
}
