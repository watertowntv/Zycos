@file:Suppress("unused")

package zaqws.zycos.simulated.goal.builtin

import zaqws.zycos.simulated.goal.SimulatedGoal
import zaqws.zycos.simulated.goal.SimulatedGoalContext
import zaqws.zycos.simulated.goal.SimulatedGoalRuntime
import zaqws.zycos.simulated.goal.SimulatedIntent

class SimulatedMeleeGoal(
    val movementPriority: Int = DEFAULT_MOVEMENT_PRIORITY,
    val attackPriority: Int = DEFAULT_ATTACK_PRIORITY,
    val lookPriority: Int = DEFAULT_LOOK_PRIORITY,
    val stoppingDistance: Double = DEFAULT_STOPPING_DISTANCE
) : SimulatedGoal {
    init {
        require(stoppingDistance.isFinite())
        require(stoppingDistance >= 0.0)
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

        val attackRange =
            context.attackRange

        val effectiveStoppingDistance =
            maxOf(
                stoppingDistance,
                attackRange
            )

        val distanceSquared =
            context.position
                .distanceSquared(
                    target.position
                )

        intents.add(
            SimulatedIntent.LookAt(
                position =
                    target.position,

                priority =
                    lookPriority
            )
        )

        if (
            distanceSquared <=
            attackRange * attackRange
        ) {
            intents.add(
                SimulatedIntent.StopMovement(
                    priority =
                        movementPriority
                )
            )

            intents.add(
                SimulatedIntent.Attack(
                    targetEntityId =
                        target.entityId,

                    priority =
                        attackPriority
                )
            )

            return
        }

        intents.add(
            SimulatedIntent.MoveTo(
                position =
                    target.position,

                stoppingDistance =
                    effectiveStoppingDistance,

                priority =
                    movementPriority
            )
        )
    }

    companion object {
        const val DEFAULT_MOVEMENT_PRIORITY = 10
        const val DEFAULT_ATTACK_PRIORITY = 10
        const val DEFAULT_LOOK_PRIORITY = 10
        const val DEFAULT_STOPPING_DISTANCE = 0.0
    }
}