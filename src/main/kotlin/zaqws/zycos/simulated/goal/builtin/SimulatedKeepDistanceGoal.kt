package zaqws.zycos.simulated.goal.builtin

import zaqws.zycos.simulated.goal.SimulatedGoal
import zaqws.zycos.simulated.goal.SimulatedGoalContext
import zaqws.zycos.simulated.goal.SimulatedGoalRuntime
import zaqws.zycos.simulated.goal.SimulatedIntent

class SimulatedKeepDistanceGoal(
    val minimumDistance: Double,
    val preferredDistance: Double,
    val movementPriority: Int = DEFAULT_MOVEMENT_PRIORITY,
    val lookPriority: Int = DEFAULT_LOOK_PRIORITY
) : SimulatedGoal {
    init {
        require(minimumDistance.isFinite())
        require(preferredDistance.isFinite())

        require(minimumDistance >= 0.0)
        require(preferredDistance >= minimumDistance)
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

        val distanceSquared =
            context.position
                .horizontalDistanceSquared(
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
            distanceSquared >=
            minimumDistance *
            minimumDistance
        ) {
            return
        }

        intents.add(
            SimulatedIntent.MoveAwayFrom(
                position =
                    target.position,

                distance =
                    preferredDistance,

                priority =
                    movementPriority
            )
        )
    }

    companion object {
        const val DEFAULT_MOVEMENT_PRIORITY = 20
        const val DEFAULT_LOOK_PRIORITY = 10
    }
}
