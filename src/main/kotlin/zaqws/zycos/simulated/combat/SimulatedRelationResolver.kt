package zaqws.zycos.simulated.combat

import zaqws.zycos.simulated.entity.SimulatedTeam

fun interface SimulatedRelationResolver {
    fun resolve(
        sourceTeam: SimulatedTeam,
        targetTeam: SimulatedTeam
    ): SimulatedRelation

    companion object {
        val DEFAULT =
            SimulatedRelationResolver {
                    sourceTeam,
                    targetTeam ->

                if (sourceTeam == targetTeam) {
                    SimulatedRelation.ALLY
                } else {
                    SimulatedRelation.ENEMY
                }
            }
    }
}