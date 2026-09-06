package zaqws.zycos

import org.bukkit.command.CommandExecutor
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.mob.MobPathfindingManager
import zaqws.zycos.mob.PathfindingManager

class Main : JavaPlugin(), CommandExecutor {
    companion object {
        lateinit var plugin: Main
            private set
    }

    override fun onEnable() {
        plugin = this

        OnlinePlayerManager.register()
        ClientEntityManager.register()
        InventoryManager.register()
        MobPathfindingManager.register()
    }

    override fun onDisable() {
        OnlinePlayerManager.unregister()
        ClientEntityManager.unregister()
        InventoryManager.unregister()
        MobPathfindingManager.unregister()
    }
}
