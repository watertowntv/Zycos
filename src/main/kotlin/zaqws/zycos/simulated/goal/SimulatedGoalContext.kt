@file:Suppress("unused")

package zaqws.zycos.simulated.goal

import zaqws.zycos.simulated.entity.SimulatedEntityFlags
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.entity.SimulatedHitbox
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.spatial.SimulatedEntityQuery

class SimulatedGoalContext internal constructor(
    private val entityStore: SimulatedEntityStore,
    private val entityQuery: SimulatedEntityQuery,
    val entityId: SimulatedEntityId,
    val tick: Long,
    val currentTargetEntityId: SimulatedEntityId?
) {
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

    fun currentTarget(): EntityView? =
        currentTargetEntityId?.let(
            ::entity
        )

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

    private fun requireSelfSlot(): Int =
        entityStore.requireSlot(
            entityId
        )

    @ConsistentCopyVisibility
    data class EntityView internal constructor(
        val entityId: SimulatedEntityId,
        val position: SimulatedVector3,
        val velocity: SimulatedVector3,
        val health: Double,
        val maximumHealth: Double,
        val hitbox: SimulatedHitbox,
        val team: SimulatedTeam,
        val flags: SimulatedEntityFlags
    ) {
        val isAlive: Boolean
            get() =
                health > 0.0
    }
}