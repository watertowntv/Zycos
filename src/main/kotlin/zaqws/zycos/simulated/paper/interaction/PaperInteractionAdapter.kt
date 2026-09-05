@file:Suppress("unused")

package zaqws.zycos.simulated.paper.interaction

import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import zaqws.zycos.simulated.math.SimulatedMath
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class PaperInteractionAdapter(
    private val interactionHandler:
    ClientEntityInteractionHandler
) {
    private val lastAttackTicks = ConcurrentHashMap<UUID, Long>()
    private var lastPruneTick = Long.MIN_VALUE

    fun handleAttack(
        player: Player,
        clientEntityId: Int
    ): Boolean {
        check(Bukkit.isPrimaryThread()) { "Interaction must occur on the main thread" }
        val currentTick = Bukkit.getCurrentTick().toLong()
        pruneStaleCooldowns(currentTick)
        val effectiveCooldown = calculateEffectiveCooldown(player, currentTick)
        if (effectiveCooldown < 0.2f) {
            return false
        }

        val baseDamage =
            player.getAttribute(
                Attribute.ATTACK_DAMAGE
            )?.value
                ?.coerceAtLeast(0.0)
                ?: DEFAULT_ATTACK_DAMAGE

        val clampedCooldown = effectiveCooldown.toDouble().coerceIn(0.0, 1.0)
        val damage = baseDamage * (0.2 + 0.8 * clampedCooldown * clampedCooldown)

        val success = interactionHandler.attack(
            player = player,
            clientEntityId = clientEntityId,
            damage = damage
        )

        if (success) {
            lastAttackTicks[player.uniqueId] = currentTick
            player.resetCooldown()
        }

        return success
    }

    fun handleInteract(
        player: Player,
        clientEntityId: Int,
        equipmentSlot: EquipmentSlot
    ): Boolean {
        check(Bukkit.isPrimaryThread()) { "Interaction must occur on the main thread" }

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
        check(Bukkit.isPrimaryThread()) { "Interaction must occur on the main thread" }
        require(damage.isFinite())
        require(damage >= 0.0)

        if (
            damage <=
            SimulatedMath.EPSILON
        ) {
            return false
        }

        val currentTick = Bukkit.getCurrentTick().toLong()
        pruneStaleCooldowns(currentTick)
        val effectiveCooldown = calculateEffectiveCooldown(player, currentTick)
        if (effectiveCooldown < 0.2f) {
            return false
        }

        val success = interactionHandler.attack(
            player = player,
            clientEntityId = clientEntityId,
            damage = damage
        )

        if (success) {
            lastAttackTicks[player.uniqueId] = currentTick
            player.resetCooldown()
        }

        return success
    }

    fun clearCooldown(uuid: UUID) {
        lastAttackTicks.remove(uuid)
    }

    fun clearCooldown(player: Player) {
        lastAttackTicks.remove(player.uniqueId)
    }

    private fun calculateEffectiveCooldown(
        player: Player,
        currentTick: Long
    ): Float {
        val lastTick = lastAttackTicks[player.uniqueId] ?: return player.attackCooldown
        val attackSpeed = player.getAttribute(Attribute.ATTACK_SPEED)?.value ?: DEFAULT_ATTACK_SPEED
        val ticksToCharge = 20.0 / attackSpeed.coerceAtLeast(0.1)
        val elapsedTicks = (currentTick - lastTick).coerceAtLeast(0L).toDouble()
        val localCooldown = (elapsedTicks / ticksToCharge).toFloat().coerceIn(0.0f, 1.0f)
        return min(player.attackCooldown, localCooldown)
    }

    private fun pruneStaleCooldowns(currentTick: Long) {
        if (
            lastPruneTick != Long.MIN_VALUE &&
            currentTick >= lastPruneTick &&
            currentTick - lastPruneTick < COOLDOWN_PRUNE_INTERVAL_TICKS
        ) {
            return
        }

        lastAttackTicks.entries.removeIf { Bukkit.getPlayer(it.key) == null }
        lastPruneTick = currentTick
    }

    companion object {
        private const val DEFAULT_ATTACK_DAMAGE = 1.0
        private const val DEFAULT_ATTACK_SPEED = 4.0
        private const val COOLDOWN_PRUNE_INTERVAL_TICKS = 20L
    }
}
