package zaqws.zycos.simulated.combat

import zaqws.zycos.simulated.entity.SimulatedEntityId

data class SimulatedDamage(
    val targetEntityId: SimulatedEntityId,
    val amount: Double,
    val sourceEntityId: SimulatedEntityId? = null
) {
    init {
        require(amount.isFinite())
        require(amount >= 0.0)
    }
}