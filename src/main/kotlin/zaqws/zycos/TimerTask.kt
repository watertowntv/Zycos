@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package zaqws.zycos

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable


abstract class TimerTask(
    minutes: Int = 1,
    seconds: Int = 0,
    var visible: Boolean = true,
    initialPlayers: List<Player> = listOf()
) {
    protected var bossBar: BossBar? = null
    protected val viewers = mutableListOf<Player>()

    protected val totalSeconds = minutes * 60 + seconds + 1
    protected var remainingSeconds = totalSeconds
    protected var remove = false

    init {
        bossBar = BossBar.bossBar(
            text("남은시간: ${minutes}분 ${seconds}초"),
            1.0f,
            BossBar.Color.GREEN,
            BossBar.Overlay.PROGRESS
        )
        viewers.addAll(initialPlayers.ifEmpty { onlinePlayers })

        if (visible) viewers.forEach { viewer ->
            viewer.showBossBar(bossBar!!)
        }

        onStart()

        object : BukkitRunnable() {
            override fun run() {
                if(remove){
                    cancel()
                    return
                }

                update()
            }
        }.runTaskTimer(Main.plugin, 0L, 20L)
    }

    private fun update() {
        onPreUpdate()

        if (remove) return
        if (--remainingSeconds <= 0) {
            remove()
            onEnd()
            return
        }

        bossBar?.apply {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60

            name(text("남은시간: ${minutes}분 ${seconds}초"))
            progress((remainingSeconds.toDouble() / totalSeconds).toFloat().coerceIn(0.0f, 1.0f))

            viewers.forEach { player ->
                if (visible) player.showBossBar(this)
                else player.hideBossBar(this)
            }
        }
    }

    open fun remove() {
        remove = true

        bossBar?.apply {
            progress(0.0f)
            viewers.forEach { it.hideBossBar(this) }
            viewers.clear()
        }

        bossBar = null
    }

    protected open fun onStart() {}
    protected open fun onEnd() {}
    protected open fun onPreUpdate() {}
}
