package zaqws.zycos.simulated.entity

import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedVector3

data class SimulatedHitbox(
    val width: Double,
    val height: Double
) {
    companion object {
        val DEFAULT = SimulatedHitbox(
            width = 1.0,
            height = 2.0
        )
    }

    init {
        require(width.isFinite())
        require(height.isFinite())
        require(width > 0.0)
        require(height > 0.0)
    }

    val halfWidth: Double
        get() = width * 0.5

    fun at(position: SimulatedVector3) =
        SimulatedAABB.fromBottomCenter(
            position,
            width,
            height
        )
}