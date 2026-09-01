package zaqws.zycos.simulated.paper.interaction

import org.bukkit.entity.Player
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.paper.player.PaperPlayerProvider

class ClientEntityInteractionHandler(
    private val engine: SimulatedEngine,
    private val playerProvider: PaperPlayerProvider,
    private val simulatedEntityResolver:
        (clientEntityId: Int) -> SimulatedEntityId?,
    private val interactionConsumer:
        (SimulatedInteraction) -> Unit = {}
) {
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
}