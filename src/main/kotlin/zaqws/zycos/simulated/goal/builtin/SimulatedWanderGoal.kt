package zaqws.zycos.simulated.goal.builtin

import zaqws.zycos.simulated.goal.SimulatedGoal
import zaqws.zycos.simulated.goal.SimulatedGoalContext
import zaqws.zycos.simulated.goal.SimulatedGoalRuntime
import zaqws.zycos.simulated.goal.SimulatedIntent
import zaqws.zycos.simulated.math.SimulatedVector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SimulatedWanderGoal(
    val minimumDistance: Double =
        DEFAULT_MINIMUM_DISTANCE,
    val maximumDistance: Double =
        DEFAULT_MAXIMUM_DISTANCE,
    val minimumIntervalTicks: Int =
        DEFAULT_MINIMUM_INTERVAL_TICKS,
    val maximumIntervalTicks: Int =
        DEFAULT_MAXIMUM_INTERVAL_TICKS,
    val stoppingDistance: Double =
        DEFAULT_STOPPING_DISTANCE,
    val movementPriority: Int =
        DEFAULT_MOVEMENT_PRIORITY
) : SimulatedGoal {
    init {
        require(minimumDistance.isFinite())
        require(maximumDistance.isFinite())
        require(stoppingDistance.isFinite())

        require(minimumDistance >= 0.0)
        require(maximumDistance >= minimumDistance)
        require(stoppingDistance >= 0.0)

        require(minimumIntervalTicks > 0)
        require(
            maximumIntervalTicks >=
                    minimumIntervalTicks
        )
    }

    override fun createRuntime():
            SimulatedGoalRuntime =
        Runtime()

    override fun evaluate(
        context: SimulatedGoalContext,
        runtime: SimulatedGoalRuntime,
        intents: MutableCollection<SimulatedIntent>
    ) {
        require(runtime is Runtime)

        if (
            context.currentTarget != null
        ) {
            runtime.targetPosition = null

            return
        }

        val existingTarget =
            runtime.targetPosition

        if (existingTarget != null) {
            val distanceSquared =
                context.position
                    .horizontalDistanceSquared(
                        existingTarget
                    )

            if (
                distanceSquared >
                stoppingDistance *
                stoppingDistance
            ) {
                intents.add(
                    SimulatedIntent.MoveTo(
                        position =
                            existingTarget,

                        stoppingDistance =
                            stoppingDistance,

                        priority =
                            movementPriority
                    )
                )

                return
            }

            runtime.targetPosition = null
        }

        if (
            context.tick <
            runtime.nextSelectionTick
        ) {
            return
        }

        val targetPosition =
            randomTarget(
                context.position
            )

        runtime.targetPosition =
            targetPosition

        runtime.nextSelectionTick =
            context.tick +
                    Random.nextInt(
                        minimumIntervalTicks,
                        maximumIntervalTicks + 1
                    )

        intents.add(
            SimulatedIntent.MoveTo(
                position =
                    targetPosition,

                stoppingDistance =
                    stoppingDistance,

                priority =
                    movementPriority
            )
        )
    }

    private fun randomTarget(
        position: SimulatedVector3
    ): SimulatedVector3 {
        val angle =
            Random.nextDouble(
                0.0,
                PI * 2.0
            )

        val distance =
            if (
                minimumDistance ==
                maximumDistance
            ) {
                minimumDistance
            } else {
                Random.nextDouble(
                    minimumDistance,
                    maximumDistance
                )
            }

        return SimulatedVector3(
            x =
                position.x +
                        cos(angle) *
                        distance,

            y =
                position.y,

            z =
                position.z +
                        sin(angle) *
                        distance
        )
    }

    private class Runtime :
        SimulatedGoalRuntime {
        var targetPosition:
                SimulatedVector3? = null

        var nextSelectionTick: Long = 0L
    }

    companion object {
        const val DEFAULT_MINIMUM_DISTANCE = 2.0
        const val DEFAULT_MAXIMUM_DISTANCE = 8.0

        const val DEFAULT_MINIMUM_INTERVAL_TICKS = 20
        const val DEFAULT_MAXIMUM_INTERVAL_TICKS = 80

        const val DEFAULT_STOPPING_DISTANCE = 0.4

        const val DEFAULT_MOVEMENT_PRIORITY = 1
    }
}
