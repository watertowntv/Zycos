@file:Suppress("unused")

package zaqws.zycos.simulated.entity

import zaqws.zycos.simulated.math.SimulatedVector3

class SimulatedEntity internal constructor(
    val entityId: SimulatedEntityId,
    private val controller: SimulatedEntityController
) {
    val exists: Boolean
        get() = controller.exists(entityId)

    fun snapshot(): SimulatedEntitySnapshot? =
        controller.snapshot(entityId)

    fun remove() {
        controller.remove(entityId)
    }

    fun teleport(
        position: SimulatedVector3,
        yaw: Float? = null,
        pitch: Float? = null
    ) {
        require(position.isFinite)
        require(yaw == null || yaw.isFinite())
        require(pitch == null || pitch.isFinite())

        controller.teleport(
            entityId,
            position,
            yaw,
            pitch
        )
    }

    fun setVelocity(velocity: SimulatedVector3) {
        require(velocity.isFinite)

        controller.setVelocity(
            entityId,
            velocity
        )
    }

    fun addVelocity(velocity: SimulatedVector3) {
        require(velocity.isFinite)

        controller.addVelocity(
            entityId,
            velocity
        )
    }

    fun damage(amount: Double) {
        require(amount.isFinite())
        require(amount >= 0.0)

        controller.damage(
            entityId,
            amount
        )
    }

    fun heal(amount: Double) {
        require(amount.isFinite())
        require(amount >= 0.0)

        controller.heal(
            entityId,
            amount
        )
    }

    fun setTeam(team: SimulatedTeam) {
        controller.setTeam(
            entityId,
            team
        )
    }

    fun setPresentation(presentationId: SimulatedPresentationId) {
        controller.setPresentation(
            entityId,
            presentationId
        )
    }

    override fun equals(other: Any?): Boolean =
        other is SimulatedEntity &&
                other.entityId == entityId &&
                other.controller === controller

    override fun hashCode(): Int =
        31 * System.identityHashCode(controller) + entityId.hashCode()

    override fun toString() =
        "SimulatedEntity(entityId=$entityId)"
}

internal interface SimulatedEntityController {
    fun exists(entityId: SimulatedEntityId): Boolean

    fun snapshot(entityId: SimulatedEntityId): SimulatedEntitySnapshot?

    fun remove(entityId: SimulatedEntityId)

    fun teleport(
        entityId: SimulatedEntityId,
        position: SimulatedVector3,
        yaw: Float?,
        pitch: Float?
    )

    fun setVelocity(
        entityId: SimulatedEntityId,
        velocity: SimulatedVector3
    )

    fun addVelocity(
        entityId: SimulatedEntityId,
        velocity: SimulatedVector3
    )

    fun damage(
        entityId: SimulatedEntityId,
        amount: Double
    )

    fun heal(
        entityId: SimulatedEntityId,
        amount: Double
    )

    fun setTeam(
        entityId: SimulatedEntityId,
        team: SimulatedTeam
    )

    fun setPresentation(
        entityId: SimulatedEntityId,
        presentationId: SimulatedPresentationId
    )
}
