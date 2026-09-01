package zaqws.zycos.simulated.paper.render

import org.bukkit.entity.EntityType
import zaqws.zycos.ClientEntityManager
import zaqws.zycos.simulated.snapshot.SimulatedEvent

class SimulatedPresentation(
    val entityType: EntityType,
    private val createHandler:
        (ClientEntityManager.ClientEntity) -> Unit = {},
    private val eventHandler:
        (
        ClientEntityManager.ClientEntity,
        SimulatedEvent
    ) -> Unit = { _, _ -> }
) {
    init {
        require(entityType.isSpawnable)
    }

    internal fun onCreate(
        clientEntity: ClientEntityManager.ClientEntity
    ) {
        createHandler(
            clientEntity
        )
    }

    internal fun onEvent(
        clientEntity: ClientEntityManager.ClientEntity,
        event: SimulatedEvent
    ) {
        eventHandler(
            clientEntity,
            event
        )
    }
}