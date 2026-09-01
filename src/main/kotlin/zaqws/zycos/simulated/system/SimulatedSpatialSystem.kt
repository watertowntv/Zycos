package zaqws.zycos.simulated.system

import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.spatial.SimulatedSpatialIndex

internal class SimulatedSpatialSystem(
    private val entityStore: SimulatedEntityStore,
    private val spatialIndex: SimulatedSpatialIndex
) : SimulatedSystem {
    override fun update(
        context: SimulatedSystemContext
    ) {
        spatialIndex.rebuild(
            entityStore
        )
    }
}
