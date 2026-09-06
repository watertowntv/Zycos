@file:Suppress("unused")

package zaqws.zycos.mob

import com.destroystokyo.paper.entity.ai.Goal
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class MobAIManager(
    private val plugin: JavaPlugin
) : Listener {
    private val contexts = HashMap<UUID, MobAIContext<*>>()

    init {
        plugin.server.pluginManager.registerEvents(
            this, plugin
        )
    }

    fun unregister() {
        val mobGoals = plugin.server.mobGoals

        contexts.values.forEach {
            if (it.mob.isValid) {
                mobGoals.removeAllGoals(it.mob)
            }

            it.cleanup()
        }

        contexts.clear()
        HandlerList.unregisterAll(this)
    }


    fun <T : Mob> apply(
        mob: T,
        profile: MobAIProfile<T>
    ): MobAIContext<T> {
        remove(mob)

        val mobGoals = plugin.server.mobGoals
        val context = MobAIContext(mob)
        val goals = profile.createGoals(context)

        mobGoals.removeAllGoals(mob)

        try {
            for ((priority, goal) in goals) {
                mobGoals.addGoal(mob, priority, goal)
            }

            contexts[mob.uniqueId] = context
        } catch (exception: RuntimeException) {
            mobGoals.removeAllGoals(mob)
            context.cleanup()

            throw exception
        }

        return context
    }

    fun remove(mob: Mob): Boolean {
        val context = contexts.remove(mob.uniqueId) ?: return false

        plugin.server.mobGoals.removeAllGoals(mob)
        context.cleanup()

        return true
    }

    fun getContext(mob: Mob): MobAIContext<*>? = contexts[mob.uniqueId]
    fun hasAI(mob: Mob): Boolean = mob.uniqueId in contexts


    fun interface MobAIProfile<T : Mob> {
        fun createGoals(context: MobAIContext<T>): Collection<GoalEntry<T>>
    }

    @EventHandler
    private fun onEntityRemove(event: EntityRemoveFromWorldEvent) {
        val mob = event.entity as? Mob ?: return

        contexts.remove(mob.uniqueId)?.cleanup()
    }

    class MobAIContext<T : Mob>(
        val mob: T
    ) {
        private val cleanupActions = ArrayList<() -> Unit>()
        var target: LivingEntity? = null

        fun onCleanup(action: () -> Unit) {
            cleanupActions.add(action)
        }

        internal fun cleanup() {
            target = null

            for (action in cleanupActions) {
                action()
            }

            cleanupActions.clear()
        }
    }

    data class GoalEntry<T : Mob>(
        val priority: Int,
        val goal: Goal<T>
    ) {
        init {
            require(priority >= 0)
        }
    }
}