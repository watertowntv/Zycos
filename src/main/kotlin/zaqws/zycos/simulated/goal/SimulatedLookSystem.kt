package zaqws.zycos.simulated.goal

import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

internal class SimulatedLookSystem(
    private val entityStore: SimulatedEntityStore,
    private val goalSystem: SimulatedGoalSystem
) : SimulatedSystem {
    override fun update(context: SimulatedSystemContext) {
        var slot = 0

        while (slot < entityStore.size) {
            if (
                !entityStore.hasFlag(slot, SimulatedEntityFlag.REMOVED) &&
                !entityStore.hasFlag(slot, SimulatedEntityFlag.DEAD)
            ) {
                updateEntity(slot)
            }

            slot++
        }
    }

    private fun updateEntity(slot: Int) {
        val intent = goalSystem.lookIntent(entityStore.entityIdAt(slot)) ?: return
        val position = entityStore.position(slot)
        val differenceX = intent.position.x - position.x
        val differenceY = intent.position.y - position.y
        val differenceZ = intent.position.z - position.z
        val horizontalDistance = sqrt(differenceX * differenceX + differenceZ * differenceZ)

        if (
            horizontalDistance <= SimulatedMath.EPSILON &&
            abs(differenceY) <= SimulatedMath.EPSILON
        ) {
            return
        }

        val yaw = (atan2(-differenceX, differenceZ) * 180.0 / PI).toFloat()
        val pitch = (-atan2(differenceY, horizontalDistance) * 180.0 / PI).toFloat()

        entityStore.setRotation(slot, yaw, pitch)
    }
}
