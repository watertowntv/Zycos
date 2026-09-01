package zaqws.zycos.simulated.paper.player

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.math.SimulatedVector3

class PaperExternalActionHandler(
    private val plugin: JavaPlugin,
    private val playerProvider: PaperPlayerProvider
) {
    fun handle(action: SimulatedExternalAction) {
        if (plugin.server.isPrimaryThread) {
            handleOnServerThread(action)
        } else {
            plugin.server.scheduler.runTask(
                plugin,
                Runnable { handleOnServerThread(action) }
            )
        }
    }

    fun handleAll(actions: Iterable<SimulatedExternalAction>) {
        if (plugin.server.isPrimaryThread) {
            for (action in actions) {
                handleOnServerThread(action)
            }
            return
        }

        val actionCopy = actions.toList()
        if (actionCopy.isEmpty()) return

        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                for (action in actionCopy) {
                    handleOnServerThread(action)
                }
            }
        )
    }

    private fun handleOnServerThread(action: SimulatedExternalAction) {
        val player = playerProvider.player(action.actorId) ?: return
        if (!player.isOnline) return

        when (action) {
            is SimulatedExternalAction.Damage -> applyDamage(player, action)
            is SimulatedExternalAction.Knockback -> applyKnockback(player, action.velocity)
        }
    }

    private fun applyDamage(
        player: Player,
        action: SimulatedExternalAction.Damage
    ) {
        if (!player.isDead && action.amount > 0.0) {
            player.damage(action.amount)
        }
    }

    private fun applyKnockback(player: Player, velocity: SimulatedVector3) {
        if (player.isDead) return

        val currentVelocity = player.velocity
        player.velocity = Vector(
            currentVelocity.x + velocity.x,
            currentVelocity.y + velocity.y,
            currentVelocity.z + velocity.z
        )
    }
}
