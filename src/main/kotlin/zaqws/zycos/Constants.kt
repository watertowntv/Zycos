@file:Suppress("unused")

package zaqws.zycos

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.security.SecureRandom
import kotlin.random.Random


val random = Random
val secureRandom = SecureRandom()
val overworld: World = Bukkit.getWorlds().first()
val onlinePlayers: List<Player>
    get() = OnlinePlayerManager.players
val miniMessage = MiniMessage.miniMessage()

object Constants {
    const val EPSILON = 1e-8
    const val CHUNK_SHIFT = 4
}