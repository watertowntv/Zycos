@file:Suppress("unused")

package zaqws.zycos.simulated.paper.player

import org.bukkit.entity.Player
import zaqws.zycos.simulated.entity.SimulatedTeam

fun interface PaperPlayerTeamResolver {
    fun resolve(
        player: Player
    ): SimulatedTeam

    companion object {
        val NONE =
            PaperPlayerTeamResolver {
                SimulatedTeam.NONE
            }

        fun fixed(
            team: SimulatedTeam
        ) = PaperPlayerTeamResolver {
            team
        }
    }
}