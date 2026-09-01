package zaqws.zycos.simulated.paper.interaction

import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import zaqws.zycos.simulated.math.SimulatedMath

class PaperInteractionAdapter(
    private val interactionHandler:
    ClientEntityInteractionHandler
) {
    fun handleAttack(
        player: Player,
        clientEntityId: Int
    ): Boolean {
        val damage =
            player.getAttribute(
                Attribute.ATTACK_DAMAGE
            )?.value
                ?.coerceAtLeast(0.0)
                ?: DEFAULT_ATTACK_DAMAGE

        return interactionHandler.attack(
            player = player,
            clientEntityId = clientEntityId,
            damage = damage
        )
    }

    fun handleInteract(
        player: Player,
        clientEntityId: Int,
        equipmentSlot: EquipmentSlot
    ): Boolean {
        val hand =
            when (equipmentSlot) {
                EquipmentSlot.HAND ->
                    SimulatedInteraction.Hand.MAIN

                EquipmentSlot.OFF_HAND ->
                    SimulatedInteraction.Hand.OFF

                else ->
                    return false
            }

        return interactionHandler.interact(
            player = player,
            clientEntityId = clientEntityId,
            hand = hand
        )
    }

    fun handleAttack(
        player: Player,
        clientEntityId: Int,
        damage: Double
    ): Boolean {
        require(damage.isFinite())
        require(damage >= 0.0)

        if (
            damage <=
            SimulatedMath.EPSILON
        ) {
            return false
        }

        return interactionHandler.attack(
            player = player,
            clientEntityId = clientEntityId,
            damage = damage
        )
    }

    companion object {
        private const val DEFAULT_ATTACK_DAMAGE =
            1.0
    }
}
