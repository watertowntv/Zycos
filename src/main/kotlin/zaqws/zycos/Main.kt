package zaqws.zycos

import org.bukkit.command.CommandExecutor
import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin(), CommandExecutor {
    companion object {
        lateinit var plugin: Main
            private set
    }

    override fun onEnable() {
        plugin = this
    }
}
