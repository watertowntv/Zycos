package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.paper.map.SimulatedMapRevision

data class SimulatedPathRequest(
    val requestId: Long,
    val entityId: SimulatedEntityId,
    val start: NavigationNode,
    val target: NavigationNode,
    val traversalProfile: SimulatedTraversalProfile,
    val maximumDropHeightUnits: Int,
    val mapRevision: SimulatedMapRevision
) {
    init {
        require(requestId > 0L)
        require(maximumDropHeightUnits >= 0)
    }
}