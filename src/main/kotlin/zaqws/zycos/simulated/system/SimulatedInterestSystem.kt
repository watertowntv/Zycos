package zaqws.zycos.simulated.system

import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.spatial.SimulatedInterestIndex

internal class SimulatedInterestSystem(
    private val entityStore: SimulatedEntityStore,
    private val interestIndex: SimulatedInterestIndex,
    private val externalFrameProvider:
        () -> SimulatedExternalFrame
) : SimulatedSystem {
    override fun update(
        context: SimulatedSystemContext
    ) {
        interestIndex.rebuild(
            externalFrameProvider()
        )

        interestIndex.updateEntitySimulationFlags(
            entityStore
        )
    }
}
