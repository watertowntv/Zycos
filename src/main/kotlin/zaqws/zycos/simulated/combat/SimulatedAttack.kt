package zaqws.zycos.simulated.combat

import zaqws.zycos.simulated.entity.SimulatedEntityId

data class SimulatedAttack(
    val attackerEntityId: SimulatedEntityId,
    val targetEntityId: SimulatedEntityId,
    val damage: Double,
    val knockbackStrength: Double
) {
    init {
        require(
            attackerEntityId !=
                    targetEntityId
        )

        require(damage.isFinite())
        require(damage >= 0.0)

        require(knockbackStrength.isFinite())
        require(knockbackStrength >= 0.0)
    }
}