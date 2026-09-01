package zaqws.zycos.simulated.goal

import zaqws.zycos.simulated.SimulatedTarget
import zaqws.zycos.simulated.entity.SimulatedEntityFlags
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.entity.SimulatedHitbox
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.external.SimulatedExternalActorFlag
import zaqws.zycos.simulated.external.SimulatedExternalActorFlags
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.spatial.SimulatedEntityQuery

class SimulatedGoalContext internal constructor(
    private val entityStore: SimulatedEntityStore,
    private val entityQuery: SimulatedEntityQuery,
    private val externalFrame: SimulatedExternalFrame,
    val entityId: SimulatedEntityId,
    val tick: Long,
    val currentTarget: SimulatedTarget?
) {
    val currentTargetEntityId: SimulatedEntityId?
        get() =
            (currentTarget as?
                    SimulatedTarget.Entity)
                ?.entityId

    val position: SimulatedVector3
        get() =
            entityStore.position(
                requireSelfSlot()
            )

    val velocity: SimulatedVector3
        get() =
            entityStore.velocity(
                requireSelfSlot()
            )

    val health: Double
        get() =
            entityStore.health(
                requireSelfSlot()
            )

    val maximumHealth: Double
        get() =
            entityStore.maximumHealth(
                requireSelfSlot()
            )

    val movementSpeed: Double
        get() =
            entityStore.movementSpeed(
                requireSelfSlot()
            )

    val attackDamage: Double
        get() =
            entityStore.attackDamage(
                requireSelfSlot()
            )

    val attackRange: Double
        get() =
            entityStore.attackRange(
                requireSelfSlot()
            )

    val hitbox: SimulatedHitbox
        get() =
            entityStore.hitbox(
                requireSelfSlot()
            )

    val team: SimulatedTeam
        get() =
            entityStore.team(
                requireSelfSlot()
            )

    val flags: SimulatedEntityFlags
        get() =
            entityStore.flags(
                requireSelfSlot()
            )

    fun entity(
        entityId: SimulatedEntityId
    ): EntityView? {
        val slot =
            entityStore.slotOf(
                entityId
            )

        if (slot < 0) {
            return null
        }

        return entityView(
            slot
        )
    }

    fun externalActor(
        actorId: SimulatedExternalActorId
    ): ExternalActorView? =
        externalFrame[actorId]?.let {
            externalActorView(it)
        }

    fun target(
        target: SimulatedTarget
    ): TargetView? =
        when (target) {
            is SimulatedTarget.Entity ->
                entity(target.entityId)

            is SimulatedTarget.ExternalActor ->
                externalActor(target.actorId)
        }

    fun currentTarget(): TargetView? =
        currentTarget?.let(
            ::target
        )

    fun nearestTarget(
        radius: Double,
        predicate: (
            TargetView
        ) -> Boolean = { true }
    ): TargetView? {
        require(radius.isFinite())
        require(radius >= 0.0)

        var nearestTarget:
                TargetView? =
            nearestEntity(
                radius
            ) {
                predicate(it)
            }

        var nearestDistanceSquared =
            nearestTarget?.let {
                position.distanceSquared(
                    it.position
                )
            } ?: radius * radius

        externalFrame.forEach { actor ->
            if (!actor.isTargetable) {
                return@forEach
            }

            val distanceSquared =
                position.distanceSquared(
                    actor.position
                )

            if (
                distanceSquared >
                nearestDistanceSquared
            ) {
                return@forEach
            }

            val candidate =
                externalActorView(actor)

            if (!predicate(candidate)) {
                return@forEach
            }

            nearestTarget = candidate
            nearestDistanceSquared =
                distanceSquared
        }

        return nearestTarget
    }

    fun nearestEntity(
        radius: Double,
        predicate: (
            EntityView
        ) -> Boolean = { true }
    ): EntityView? {
        require(radius.isFinite())
        require(radius >= 0.0)

        val nearestEntityId =
            entityQuery.nearest(
                position = position,
                radius = radius,
                excludingEntityId = entityId
            ) { slot ->
                predicate(
                    entityView(slot)
                )
            } ?: return null

        return entity(
            nearestEntityId
        )
    }

    fun entitiesWithin(
        radius: Double,
        predicate: (
            EntityView
        ) -> Boolean = { true }
    ): List<EntityView> {
        require(radius.isFinite())
        require(radius >= 0.0)

        val entityIds =
            entityQuery.withinRadius(
                position = position,
                radius = radius,
                excludingEntityId = entityId
            ) { slot ->
                predicate(
                    entityView(slot)
                )
            }

        val entities =
            ArrayList<EntityView>(
                entityIds.size
            )

        for (
        targetEntityId in
        entityIds
        ) {
            val entity =
                entity(
                    targetEntityId
                ) ?: continue

            entities.add(entity)
        }

        return entities
    }

    fun distanceSquared(
        targetEntityId: SimulatedEntityId
    ): Double {
        val target =
            entity(
                targetEntityId
            ) ?: return Double.POSITIVE_INFINITY

        return position.distanceSquared(
            target.position
        )
    }

    fun horizontalDistanceSquared(
        targetEntityId: SimulatedEntityId
    ): Double {
        val target =
            entity(
                targetEntityId
            ) ?: return Double.POSITIVE_INFINITY

        return position.horizontalDistanceSquared(
            target.position
        )
    }

    private fun entityView(
        slot: Int
    ) = EntityView(
        target =
            SimulatedTarget.Entity(
                entityStore.entityIdAt(slot)
            ),

        entityId =
            entityStore.entityIdAt(slot),

        position =
            entityStore.position(slot),

        velocity =
            entityStore.velocity(slot),

        health =
            entityStore.health(slot),

        maximumHealth =
            entityStore.maximumHealth(slot),

        hitbox =
            entityStore.hitbox(slot),

        team =
            entityStore.team(slot),

        flags =
            entityStore.flags(slot)
    )

    private fun externalActorView(
        actor: zaqws.zycos.simulated.external.SimulatedExternalActorSnapshot
    ) = ExternalActorView(
        target =
            SimulatedTarget.ExternalActor(
                actor.actorId
            ),
        actorId = actor.actorId,
        position = actor.position,
        velocity = actor.velocity,
        health = actor.health,
        maximumHealth = actor.maximumHealth,
        hitbox = actor.hitbox,
        team = actor.team,
        flags = actor.flags
    )

    private fun requireSelfSlot(): Int =
        entityStore.requireSlot(
            entityId
        )

    sealed interface TargetView {
        val target: SimulatedTarget
        val position: SimulatedVector3
        val velocity: SimulatedVector3
        val health: Double
        val maximumHealth: Double
        val hitbox: SimulatedHitbox
        val team: SimulatedTeam
        val isAlive: Boolean
        val isTargetable: Boolean
    }

    @ConsistentCopyVisibility
    data class EntityView internal constructor(
        override val target: SimulatedTarget.Entity,
        val entityId: SimulatedEntityId,
        override val position: SimulatedVector3,
        override val velocity: SimulatedVector3,
        override val health: Double,
        override val maximumHealth: Double,
        override val hitbox: SimulatedHitbox,
        override val team: SimulatedTeam,
        val flags: SimulatedEntityFlags
    ) : TargetView {
        override val isAlive: Boolean
            get() =
                health > 0.0 &&
                        SimulatedEntityFlag.DEAD !in
                        flags

        override val isTargetable: Boolean
            get() =
                isAlive &&
                        SimulatedEntityFlag.REMOVED !in
                        flags
    }

    @ConsistentCopyVisibility
    data class ExternalActorView internal constructor(
        override val target:
            SimulatedTarget.ExternalActor,
        val actorId: SimulatedExternalActorId,
        override val position: SimulatedVector3,
        override val velocity: SimulatedVector3,
        override val health: Double,
        override val maximumHealth: Double,
        override val hitbox: SimulatedHitbox,
        override val team: SimulatedTeam,
        val flags: SimulatedExternalActorFlags
    ) : TargetView {
        override val isAlive: Boolean
            get() =
                health > 0.0 &&
                        SimulatedExternalActorFlag.ALIVE in
                        flags

        override val isTargetable: Boolean
            get() =
                isAlive &&
                        SimulatedExternalActorFlag.TARGETABLE in
                        flags
    }
}
