package zaqws.zycos.simulated.combat

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedTeam

enum class SimulatedRelation {
    ALLY,
    NEUTRAL,
    ENEMY
}

fun interface SimulatedRelationResolver {
    fun resolve(sourceTeam: SimulatedTeam, targetTeam: SimulatedTeam): SimulatedRelation

    companion object {
        val DEFAULT = SimulatedRelationResolver { sourceTeam, targetTeam ->
            if (sourceTeam == targetTeam) SimulatedRelation.ALLY
            else SimulatedRelation.ENEMY
        }
    }
}

data class SimulatedDamage(
    val targetEntityId: SimulatedEntityId,
    val amount: Double,
    val sourceEntityId: SimulatedEntityId? = null
) {
    init {
        require(amount.isFinite() && amount >= 0.0)
    }
}

data class SimulatedAttack(
    val attackerEntityId: SimulatedEntityId,
    val targetEntityId: SimulatedEntityId,
    val damage: Double,
    val knockbackStrength: Double
) {
    init {
        require(attackerEntityId != targetEntityId)
        require(damage.isFinite() && damage >= 0.0)
        require(knockbackStrength.isFinite() && knockbackStrength >= 0.0)
    }
}
