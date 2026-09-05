package zaqws.zycos.simulated.command

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntitySpawnData
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.projectile.SimulatedProjectileId
import zaqws.zycos.simulated.projectile.SimulatedProjectileSpawnData
import java.util.concurrent.ConcurrentLinkedQueue

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

    data class SpawnProjectile(
        val projectileId: SimulatedProjectileId,
        val data: SimulatedProjectileSpawnData
    ) : SimulatedCommand

    data class RemoveProjectile(
        val projectileId: SimulatedProjectileId
    ) : SimulatedCommand

    data class TeleportProjectile(
        val projectileId: SimulatedProjectileId,
        val position: SimulatedVector3
    ) : SimulatedCommand

    data class SetProjectileVelocity(
        val projectileId: SimulatedProjectileId,
        val velocity: SimulatedVector3
    ) : SimulatedCommand

    data class AddProjectileVelocity(
        val projectileId: SimulatedProjectileId,
        val velocity: SimulatedVector3
    ) : SimulatedCommand
}

internal class SimulatedCommandQueue {
    private val queue = ConcurrentLinkedQueue<SimulatedCommand>()

    fun offer(command: SimulatedCommand): Boolean {
        return queue.offer(command)
    }

    fun drain(
        maximumCommands: Int = Int.MAX_VALUE,
        consumer: (SimulatedCommand) -> Unit
    ): Int {
        require(maximumCommands >= 0)

        var processedCommands = 0

        while (processedCommands < maximumCommands) {
            val command = queue.poll() ?: break
            consumer(command)
            processedCommands++
        }

        return processedCommands
    }

    fun clear() {
        queue.clear()
    }
}
