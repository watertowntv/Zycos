package zaqws.zycos.simulated

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.external.SimulatedExternalActorId

sealed interface SimulatedTarget {
    data class Entity(val entityId: SimulatedEntityId) : SimulatedTarget
    data class ExternalActor(val actorId: SimulatedExternalActorId) : SimulatedTarget
}

data class SimulatedTimingSnapshot(
    val measuredTicks: Long,
    val latestTickNanoseconds: Long,
    val averageTickNanoseconds: Long,
    val maximumTickNanoseconds: Long
) {
    init {
        require(measuredTicks >= 0L)
        require(latestTickNanoseconds >= 0L)
        require(averageTickNanoseconds >= 0L)
        require(maximumTickNanoseconds >= 0L)
    }
}
