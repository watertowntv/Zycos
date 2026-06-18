@file:Suppress("unused")

package zaqws.zycos

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Team
import java.util.function.Predicate

class TargetFilter(
    private val entity: Entity,
    private val team: Team? = Bukkit.getScoreboardManager().mainScoreboard.getEntryTeam(
        if (entity is Player) entity.name else entity.uniqueId.toString()
    )
) : Predicate<Entity> {
    override fun test(target: Entity): Boolean {
        if (target === entity) return false
        if (target !is LivingEntity) return false
        if (entity in target.passengers) return false

        if (target.isValid && target.isDead) return false
        if (target is Player && !target.isDamageable) return false

        if (team == null || team.allowFriendlyFire()) return true


        return !team.hasEntry(
            if (target is Player) target.name else target.uniqueId.toString()
        )
    }
}
