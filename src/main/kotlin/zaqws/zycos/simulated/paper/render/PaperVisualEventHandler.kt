@file:Suppress("unused")

package zaqws.zycos.simulated.paper.render

import zaqws.zycos.ClientEntityManager
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.snapshot.SimulatedEvent

class PaperVisualEventHandler(
    private val presentationRegistry:
    SimulatedPresentationRegistry,
    private val clientEntityProvider:
        (
        SimulatedEntityId
    ) -> ClientEntityManager.ClientEntity?,
    private val presentationIdProvider:
        (
        SimulatedEntityId
    ) -> SimulatedPresentationId?
) {
    fun handle(
        event: SimulatedEvent
    ) {
        val entityId =
            event.entityId()

        val clientEntity =
            clientEntityProvider(
                entityId
            ) ?: return

        val presentationId =
            presentationIdProvider(
                entityId
            ) ?: return

        val presentation =
            presentationRegistry[
                presentationId
            ] ?: return

        presentation.onEvent(
            clientEntity,
            event
        )
    }

    fun handleAll(
        events: Iterable<SimulatedEvent>
    ) {
        for (event in events) {
            handle(event)
        }
    }

    private fun SimulatedEvent.entityId():
            SimulatedEntityId =
        when (this) {
            is SimulatedEvent.Spawn ->
                entityId

            is SimulatedEvent.Remove ->
                entityId

            is SimulatedEvent.Attack ->
                entityId

            is SimulatedEvent.Hurt ->
                entityId

            is SimulatedEvent.Death ->
                entityId

            is SimulatedEvent.Jump ->
                entityId

            is SimulatedEvent.Knockback ->
                entityId
        }
}