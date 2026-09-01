@file:Suppress("unused")

package zaqws.zycos.simulated.goal.builtin

import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.goal.SimulatedGoal
import zaqws.zycos.simulated.goal.SimulatedGoalContext
import zaqws.zycos.simulated.goal.SimulatedGoalRuntime
import zaqws.zycos.simulated.goal.SimulatedIntent

class SimulatedNearestTargetGoal(
    val searchRadius: Double,
    val retainRadius: Double =
        searchRadius * DEFAULT_RETAIN_RADIUS_MULTIPLIER,
    val priority: Int = DEFAULT_PRIORITY,
    private val relationPredicate: (
        sourceTeam: SimulatedTeam,
        targetTeam: SimulatedTeam
    ) -> Boolean = DEFAULT_RELATION_PREDICATE
) : SimulatedGoal {
    init {
        require(searchRadius.isFinite())
        require(searchRadius > 0.0)

        require(retainRadius.isFinite())
        require(retainRadius >= searchRadius)
    }

    override fun evaluate(
        context: SimulatedGoalContext,
        runtime: SimulatedGoalRuntime,
        intents: MutableCollection<SimulatedIntent>
    ) {
        val currentTarget =
            context.currentTarget()

        if (
            currentTarget != null &&
            isValidTarget(
                context,
                currentTarget
            ) &&
            context.position
                .distanceSquared(
                    currentTarget.position
                ) <=
            retainRadius * retainRadius
        ) {
            return
        }

        val nearestTarget =
            context.nearestEntity(
                searchRadius
            ) { candidate ->
                isValidTarget(
                    context,
                    candidate
                )
            }

        intents.add(
            SimulatedIntent.SetTarget(
                targetEntityId =
                    nearestTarget?.entityId,

                priority =
                    priority
            )
        )
    }

    private fun isValidTarget(
        context: SimulatedGoalContext,
        target: SimulatedGoalContext.EntityView
    ): Boolean {
        if (!target.isAlive) {
            return false
        }

        return relationPredicate(
            context.team,
            target.team
        )
    }

    companion object {
        const val DEFAULT_PRIORITY = 0

        const val DEFAULT_RETAIN_RADIUS_MULTIPLIER =
            1.25

        val DEFAULT_RELATION_PREDICATE:
                    (
            SimulatedTeam,
            SimulatedTeam
        ) -> Boolean =
            { sourceTeam, targetTeam ->
                sourceTeam !=
                        targetTeam
            }
    }
}