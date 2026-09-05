@file:Suppress("unused")

package zaqws.zycos.simulated.paper.player

import kotlinx.coroutines.launch
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.CoroutineManager.scope
import zaqws.zycos.simulated.external.SimulatedExternalAction
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.paper.toBukkitVector

class PaperExternalActionHandler @JvmOverloads constructor(
    private val plugin: JavaPlugin,
    private val playerProvider: PaperPlayerProvider,
    val world: World? = null
) {
    fun handle(action: SimulatedExternalAction) {
        if (plugin.server.isPrimaryThread) {
            handleOnServerThread(action)
        } else {
            plugin.scope.launch {
                handleOnServerThread(action)
            }
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

        plugin.scope.launch {
            for (action in actionCopy) {
                handleOnServerThread(action)
            }
        }
    }

    private fun handleOnServerThread(action: SimulatedExternalAction) {
        val player = playerProvider.player(action.actorId) ?: return
        if (!player.isOnline) return
        if (world != null && player.world != world) return

        when (action) {
            is SimulatedExternalAction.Damage -> applyDamage(player, action)
            is SimulatedExternalAction.Knockback -> applyKnockback(player, action.velocity)
            is SimulatedExternalAction.Combined -> {
                if (!player.isDead && action.damage > 0.0) {
                    player.damage(action.damage)
                }
                if (action.knockbackVelocity != null) {
                    applyKnockback(player, action.knockbackVelocity)
                }
            }
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
        player.velocity =
            currentVelocity.add(
                velocity.toBukkitVector()
            )
    }
}
