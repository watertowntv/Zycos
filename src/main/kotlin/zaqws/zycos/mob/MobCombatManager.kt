@file:Suppress("unused")

package zaqws.zycos.mob

import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.util.BoundingBox
import kotlin.math.max

object MobCombatManager {
    fun attack(
        attacker: Mob,
        target: LivingEntity,
        config: CombatConfig,
        state: CombatState = CombatState()
    ): Boolean {
        if (!attacker.isValid || attacker.isDead) return false
        if (!target.isValid || target.isDead) return false
        if (attacker === target || attacker.world !== target.world) return false

        val currentTick = Bukkit.getCurrentTick().toLong()
        if (currentTick < state.nextAttackTick) return false

        if (!isInRange(attacker, target, config)) {
            return false
        }

        attacker.attack(target)
        state.nextAttackTick = currentTick + config.attackCooldownTicks

        return true
    }

    fun isInRange(
        attacker: Mob,
        target: LivingEntity,
        config: CombatConfig
    ) = distanceSquared(attacker.boundingBox, target.boundingBox) <= config.attackRange * config.attackRange


    private fun distanceSquared(first: BoundingBox, second: BoundingBox): Double {
        val distanceX = max(0.0, max(first.minX - second.maxX, second.minX - first.maxX))
        val distanceY = max(0.0, max(first.minY - second.maxY, second.minY - first.maxY))
        val distanceZ = max(0.0, max(first.minZ - second.maxZ, second.minZ - first.maxZ))

        return distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ
    }


    data class CombatConfig(
        val attackRange: Double,
        val attackCooldownTicks: Int
    ) {
        init {
            require(attackRange.isFinite() && attackRange >= 0.0)
            require(attackCooldownTicks >= 0)
        }
    }

    class CombatState {
        internal var nextAttackTick = Long.MIN_VALUE
    }
}