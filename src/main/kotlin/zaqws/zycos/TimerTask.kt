@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package zaqws.zycos

import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

abstract class TimerTask(
    minutes: Int = 1,
    seconds: Int = 0,
    var visible: Boolean = true,
    val initialPlayers: List<Player> = listOf()
) {
    protected var bossBar: BossBar? = null
    protected val totalSeconds = minutes * 60 + seconds + 1
    protected var remainingSeconds = totalSeconds
    protected var remove = false

    init {
        bossBar = Bukkit.createBossBar("남은시간: ${minutes}분 ${seconds}초", BarColor.GREEN, BarStyle.SOLID).apply {
            isVisible = visible
            progress = 1.0

            initialPlayers.ifEmpty {
                onlinePlayers
            }.forEach(this::addPlayer)
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
            isVisible = visible

            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60

            setTitle("남은시간: ${minutes}분 ${seconds}초")

            progress = remainingSeconds.toDouble() / totalSeconds
        }
    }

    open fun remove() {
        remove = true

        bossBar?.apply {
            progress = 0.0
            isVisible = false
            removeAll()
        }

        bossBar = null
    }

    protected open fun onStart() {}
    protected open fun onEnd() {}
    protected open fun onPreUpdate() {}
}
