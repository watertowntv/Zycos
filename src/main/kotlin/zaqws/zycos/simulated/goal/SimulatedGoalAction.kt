@file:Suppress("unused")

package zaqws.zycos.simulated.goal

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import zaqws.zycos.simulated.SimulatedTarget
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.projectile.SimulatedProjectileDefinition
import zaqws.zycos.simulated.signal.SimulatedSignal

internal sealed interface SimulatedGoalAction {
    val sourceEntityId: SimulatedEntityId

    data class SetVelocity(
        override val sourceEntityId: SimulatedEntityId,
        val velocity: SimulatedVector3,
        val priority: Int
    ) : SimulatedGoalAction

    data class AddVelocity(
        override val sourceEntityId: SimulatedEntityId,
        val velocity: SimulatedVector3
    ) : SimulatedGoalAction

    data class Teleport(
        override val sourceEntityId: SimulatedEntityId,
        val position: SimulatedVector3,
        val priority: Int
    ) : SimulatedGoalAction

    data class Damage(
        override val sourceEntityId: SimulatedEntityId,
        val target: SimulatedTarget,
        val amount: Double
    ) : SimulatedGoalAction

    data class Heal(
        override val sourceEntityId: SimulatedEntityId,
        val targetEntityId: SimulatedEntityId,
        val amount: Double
    ) : SimulatedGoalAction

    data class Knockback(
        override val sourceEntityId: SimulatedEntityId,
        val target: SimulatedTarget,
        val velocity: SimulatedVector3
    ) : SimulatedGoalAction

    data class SpawnProjectile(
        override val sourceEntityId: SimulatedEntityId,
        val position: SimulatedVector3,
        val velocity: SimulatedVector3,
        val definition: SimulatedProjectileDefinition
    ) : SimulatedGoalAction

    data class EmitSignal(
        override val sourceEntityId: SimulatedEntityId,
        val signal: SimulatedSignal
    ) : SimulatedGoalAction
}

internal class SimulatedGoalActionBuffer {
    private val teleports =
        Int2ObjectOpenHashMap<SimulatedGoalAction.Teleport>()

    private val velocities =
        Int2ObjectOpenHashMap<SimulatedGoalAction.SetVelocity>()

    private val actions =
        ArrayList<SimulatedGoalAction>()

    fun offer(action: SimulatedGoalAction) {
        when (action) {
            is SimulatedGoalAction.Teleport -> {
                val key = action.sourceEntityId.value
                val current = teleports[key]

                if (
                    current == null ||
                    action.priority > current.priority
                ) {
                    teleports.put(key, action)
                }
            }

            is SimulatedGoalAction.SetVelocity -> {
                val key = action.sourceEntityId.value
                val current = velocities[key]

                if (
                    current == null ||
                    action.priority > current.priority
                ) {
                    velocities.put(key, action)
                }
            }

            else -> actions.add(action)
        }
    }

    fun forEach(action: (SimulatedGoalAction) -> Unit) {
        teleports.values.forEach(action)
        velocities.values.forEach(action)
        actions.forEach(action)
    }

    fun clear() {
        teleports.clear()
        velocities.clear()
        actions.clear()
    }
}
