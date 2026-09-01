package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.paper.map.SimulatedMapRevision

sealed interface SimulatedPathResult {
    val requestId: Long
    val entityId: SimulatedEntityId
    val mapRevision: SimulatedMapRevision

    data class Success(
        override val requestId: Long,
        override val entityId: SimulatedEntityId,
        override val mapRevision: SimulatedMapRevision,
        val path: SimulatedPath,
        val totalCost: Double
    ) : SimulatedPathResult {
        init {
            require(totalCost.isFinite())
            require(totalCost >= 0.0)
        }
    }

    data class Unreachable(
        override val requestId: Long,
        override val entityId: SimulatedEntityId,
        override val mapRevision: SimulatedMapRevision
    ) : SimulatedPathResult

    data class Invalid(
        override val requestId: Long,
        override val entityId: SimulatedEntityId,
        override val mapRevision: SimulatedMapRevision
    ) : SimulatedPathResult
}