package zaqws.zycos.simulated

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.external.SimulatedExternalActorId

sealed interface SimulatedTarget {
    data class Entity(
        val entityId: SimulatedEntityId
    ) : SimulatedTarget

    data class ExternalActor(
        val actorId: SimulatedExternalActorId
    ) : SimulatedTarget
}
