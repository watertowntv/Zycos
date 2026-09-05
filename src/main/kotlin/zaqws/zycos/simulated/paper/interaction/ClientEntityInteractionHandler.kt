package zaqws.zycos.simulated.paper.interaction

import org.bukkit.entity.Player
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.paper.player.PaperPlayerProvider

class ClientEntityInteractionHandler @JvmOverloads constructor(
    private val engine: SimulatedEngine,
    private val playerProvider: PaperPlayerProvider,
    private val simulatedEntityResolver:
        (clientEntityId: Int) -> SimulatedEntityId?,
    private val interactionConsumer:
        (SimulatedInteraction) -> Unit = {},
    val maximumReachDistance: Double = DEFAULT_MAXIMUM_REACH_DISTANCE
) {
    companion object {
        const val DEFAULT_MAXIMUM_REACH_DISTANCE = 6.0
    }

    init {
        require(maximumReachDistance.isFinite())
        require(maximumReachDistance > 0.0)
    }

    fun attack(
        player: Player,
        clientEntityId: Int,
        damage: Double
    ): Boolean {
        require(damage.isFinite())
        require(damage >= 0.0)

        val targetEntityId =
            resolveTarget(
                clientEntityId
            ) ?: return false

        if (!validateInteraction(player, targetEntityId)) {
            return false
        }

        val actorId =
            playerProvider.actorId(
                player
            )

        val interaction =
            SimulatedInteraction.Attack(
                actorId = actorId,
                targetEntityId = targetEntityId,
                damage = damage
            )

        engine.getEntity(
            targetEntityId
        )?.damage(
            damage
        ) ?: return false

        interactionConsumer(
            interaction
        )

        return true
    }

    fun interact(
        player: Player,
        clientEntityId: Int,
        hand: SimulatedInteraction.Hand
    ): Boolean {
        val targetEntityId =
            resolveTarget(
                clientEntityId
            ) ?: return false

        if (!validateInteraction(player, targetEntityId)) {
            return false
        }

        val actorId =
            playerProvider.actorId(
                player
            )

        interactionConsumer(
            SimulatedInteraction.Interact(
                actorId = actorId,
                targetEntityId = targetEntityId,
                hand = hand
            )
        )

        return true
    }

    fun resolveTarget(
        clientEntityId: Int
    ): SimulatedEntityId? {
        val simulatedEntityId =
            simulatedEntityResolver(
                clientEntityId
            ) ?: return null

        if (
            !engine.exists(
                simulatedEntityId
            )
        ) {
            return null
        }

        return simulatedEntityId
    }

    private fun validateInteraction(
        player: Player,
        targetEntityId: SimulatedEntityId
    ): Boolean {
        if (playerProvider.world != null && player.world != playerProvider.world) {
            return false
        }

        val snapshot = engine.snapshot(targetEntityId) ?: return false

        val targetPosition = snapshot.position
        val loc = player.location
        val eyeLoc = player.eyeLocation

        val feetDx = loc.x - targetPosition.x
        val feetDy = loc.y - targetPosition.y
        val feetDz = loc.z - targetPosition.z
        val feetDistanceSquared = feetDx * feetDx + feetDy * feetDy + feetDz * feetDz

        val eyeDx = eyeLoc.x - targetPosition.x
        val eyeDy = eyeLoc.y - targetPosition.y
        val eyeDz = eyeLoc.z - targetPosition.z
        val eyeDistanceSquared = eyeDx * eyeDx + eyeDy * eyeDy + eyeDz * eyeDz

        val maximumReachDistanceSquared = maximumReachDistance * maximumReachDistance

        val minDistanceSquared =
            if (feetDistanceSquared < eyeDistanceSquared) {
                feetDistanceSquared
            } else {
                eyeDistanceSquared
            }

        return minDistanceSquared <= maximumReachDistanceSquared
    }
}