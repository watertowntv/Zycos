package zaqws.zycos.simulated.command

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntitySpawnData
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.math.SimulatedVector3

internal sealed interface SimulatedCommand {
    data class Spawn(
        val entityId: SimulatedEntityId,
        val data: SimulatedEntitySpawnData
    ) : SimulatedCommand

    data class Remove(
        val entityId: SimulatedEntityId
    ) : SimulatedCommand

    data class Teleport(
        val entityId: SimulatedEntityId,
        val position: SimulatedVector3,
        val yaw: Float?,
        val pitch: Float?
    ) : SimulatedCommand

    data class SetVelocity(
        val entityId: SimulatedEntityId,
        val velocity: SimulatedVector3
    ) : SimulatedCommand

    data class AddVelocity(
        val entityId: SimulatedEntityId,
        val velocity: SimulatedVector3
    ) : SimulatedCommand

    data class Damage(
        val entityId: SimulatedEntityId,
        val amount: Double
    ) : SimulatedCommand

    data class Heal(
        val entityId: SimulatedEntityId,
        val amount: Double
    ) : SimulatedCommand

    data class SetTeam(
        val entityId: SimulatedEntityId,
        val team: SimulatedTeam
    ) : SimulatedCommand

    data class SetPresentation(
        val entityId: SimulatedEntityId,
        val presentationId: SimulatedPresentationId
    ) : SimulatedCommand
}