@file:Suppress("unused")

package zaqws.zycos.simulated.entity

import zaqws.zycos.simulated.math.SimulatedVector3

data class SimulatedEntitySnapshot(
    val entityId: SimulatedEntityId,
    val position: SimulatedVector3,
    val velocity: SimulatedVector3,
    val yaw: Float,
    val pitch: Float,
    val hitbox: SimulatedHitbox,
    val health: Double,
    val attributes: SimulatedAttributes,
    val team: SimulatedTeam,
    val presentationId: SimulatedPresentationId,
    val flags: SimulatedEntityFlags
) {
    val isAlive: Boolean
        get() = health > 0.0 &&
                SimulatedEntityFlag.DEAD !in flags &&
                SimulatedEntityFlag.REMOVED !in flags

    val isOnGround: Boolean
        get() = SimulatedEntityFlag.ON_GROUND in flags

    val isFullSimulation: Boolean
        get() = SimulatedEntityFlag.FULL_SIMULATION in flags
}