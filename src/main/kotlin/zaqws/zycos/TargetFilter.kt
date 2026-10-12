@file:Suppress("unused")

package zaqws.zycos

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.function.Predicate

class TargetFilter(
    private val entities: Set<Entity>
) : Predicate<Entity> {
    private fun getTeam(entity: Entity) = Bukkit.getScoreboardManager().mainScoreboard.getEntryTeam(
        if (entity is Player) entity.name else entity.uniqueId.toString()
    )

    override fun test(target: Entity): Boolean {
        if (target in entities) return false
        if (target !is LivingEntity) return false
        if (entities.any(target.passengers::contains)) return false

        if (target.isInvulnerable) return false
        if (!target.isValid || target.isDead) return false
        if (target is Player && !target.isDamageable) return false

        val targetEntry = if (target is Player) target.name else target.uniqueId.toString()

        return entities.none { entity ->
            val team = getTeam(entity)

            team != null && !team.allowFriendlyFire() && team.hasEntry(targetEntry)
        }
    }
}
