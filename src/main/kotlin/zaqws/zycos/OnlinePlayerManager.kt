package zaqws.zycos

import io.papermc.paper.event.server.ServerResourcesReloadedEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

internal object OnlinePlayerManager : Listener {
    internal val players = arrayListOf<Player>()

    internal fun initialize() {
        players.clear()
        players.addAll(Bukkit.getOnlinePlayers())

        Main.plugin.server.pluginManager.registerEvents(
            this, Main.plugin
        )
    }

    internal fun unregister() {
        HandlerList.unregisterAll(this)
    }

    @EventHandler
    private fun onJoin(event: PlayerJoinEvent) {
        players.add(event.player)
    }

    @EventHandler
    private fun onQuit(event: PlayerQuitEvent) {
        players.remove(event.player)
    }

    @Suppress("unused")
    @EventHandler
    private fun onReload(event: ServerResourcesReloadedEvent) {
        players.clear()
        players.addAll(Bukkit.getOnlinePlayers())
    }
}