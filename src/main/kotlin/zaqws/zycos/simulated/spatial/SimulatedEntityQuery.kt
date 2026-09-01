@file:Suppress("unused")

package zaqws.zycos.simulated.spatial

import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3

internal class SimulatedEntityQuery(
    private val entityStore: SimulatedEntityStore,
    private val spatialIndex: SimulatedSpatialIndex
) {
    fun nearest(
        position: SimulatedVector3,
        radius: Double,
        excludingEntityId: SimulatedEntityId? = null,
        predicate: (slot: Int) -> Boolean = { true }
    ): SimulatedEntityId? {
        require(position.isFinite)
        require(radius.isFinite())
        require(radius >= 0.0)

        val radiusSquared =
            radius * radius

        var nearestEntityId:
                SimulatedEntityId? = null

        var nearestDistanceSquared =
            Double.POSITIVE_INFINITY

        spatialIndex.forEachNearby(
            position,
            radius
        ) { slot ->
            if (!isQueryable(slot)) {
                return@forEachNearby
            }

            val entityId =
                entityStore.entityIdAt(slot)

            if (
                excludingEntityId != null &&
                entityId == excludingEntityId
            ) {
                return@forEachNearby
            }

            if (!predicate(slot)) {
                return@forEachNearby
            }

            val candidatePosition =
                entityStore.position(slot)

            val distanceSquared =
                position.distanceSquared(
                    candidatePosition
                )

            if (
                distanceSquared >
                radiusSquared
            ) {
                return@forEachNearby
            }

            if (
                distanceSquared <
                nearestDistanceSquared
            ) {
                nearestDistanceSquared =
                    distanceSquared

                nearestEntityId =
                    entityId
            }
        }

        return nearestEntityId
    }

    fun nearestEnemy(
        sourceEntityId: SimulatedEntityId,
        radius: Double,
        relationPredicate: (
            sourceTeam: SimulatedTeam,
            targetTeam: SimulatedTeam
        ) -> Boolean
    ): SimulatedEntityId? {
        val sourceSlot =
            entityStore.slotOf(
                sourceEntityId
            )

        if (sourceSlot < 0) {
            return null
        }

        val sourcePosition =
            entityStore.position(
                sourceSlot
            )

        val sourceTeam =
            entityStore.team(
                sourceSlot
            )

        return nearest(
            position = sourcePosition,
            radius = radius,
            excludingEntityId =
                sourceEntityId
        ) { targetSlot ->
            relationPredicate(
                sourceTeam,
                entityStore.team(
                    targetSlot
                )
            )
        }
    }

    fun withinRadius(
        position: SimulatedVector3,
        radius: Double,
        excludingEntityId: SimulatedEntityId? = null,
        predicate: (slot: Int) -> Boolean = { true }
    ): List<SimulatedEntityId> {
        require(position.isFinite)
        require(radius.isFinite())
        require(radius >= 0.0)

        val radiusSquared =
            radius * radius

        val result =
            ArrayList<SimulatedEntityId>()

        spatialIndex.forEachNearby(
            position,
            radius
        ) { slot ->
            if (!isQueryable(slot)) {
                return@forEachNearby
            }

            val entityId =
                entityStore.entityIdAt(slot)

            if (
                excludingEntityId != null &&
                entityId == excludingEntityId
            ) {
                return@forEachNearby
            }

            if (!predicate(slot)) {
                return@forEachNearby
            }

            if (
                position.distanceSquared(
                    entityStore.position(slot)
                ) >
                radiusSquared
            ) {
                return@forEachNearby
            }

            result.add(
                entityId
            )
        }

        return result
    }

    fun withinAabb(
        boundingBox: SimulatedAABB,
        excludingEntityId: SimulatedEntityId? = null,
        predicate: (slot: Int) -> Boolean = { true }
    ): List<SimulatedEntityId> {
        val minimumCell =
            spatialIndex.cellOf(
                SimulatedVector3(
                    boundingBox.minimumX,
                    boundingBox.minimumY,
                    boundingBox.minimumZ
                )
            )

        val maximumCell =
            spatialIndex.cellOf(
                SimulatedVector3(
                    boundingBox.maximumX -
                            SimulatedMath.EPSILON,
                    boundingBox.maximumY -
                            SimulatedMath.EPSILON,
                    boundingBox.maximumZ -
                            SimulatedMath.EPSILON
                )
            )

        val result =
            ArrayList<SimulatedEntityId>()

        spatialIndex.forEachCell(
            minimumCellX =
                minimumCell.x,

            minimumCellY =
                minimumCell.y,

            minimumCellZ =
                minimumCell.z,

            maximumCellX =
                maximumCell.x,

            maximumCellY =
                maximumCell.y,

            maximumCellZ =
                maximumCell.z
        ) { slot ->
            if (!isQueryable(slot)) {
                return@forEachCell
            }

            val entityId =
                entityStore.entityIdAt(slot)

            if (
                excludingEntityId != null &&
                entityId == excludingEntityId
            ) {
                return@forEachCell
            }

            if (!predicate(slot)) {
                return@forEachCell
            }

            val entityBoundingBox =
                entityStore.hitbox(slot)
                    .at(
                        entityStore.position(slot)
                    )

            if (
                !boundingBox.intersects(
                    entityBoundingBox
                )
            ) {
                return@forEachCell
            }

            result.add(
                entityId
            )
        }

        return result
    }

    fun countWithinRadius(
        position: SimulatedVector3,
        radius: Double,
        predicate: (slot: Int) -> Boolean = { true }
    ): Int {
        require(position.isFinite)
        require(radius.isFinite())
        require(radius >= 0.0)

        val radiusSquared =
            radius * radius

        var count = 0

        spatialIndex.forEachNearby(
            position,
            radius
        ) { slot ->
            if (
                isQueryable(slot) &&
                predicate(slot) &&
                position.distanceSquared(
                    entityStore.position(slot)
                ) <= radiusSquared
            ) {
                count++
            }
        }

        return count
    }

    private fun isQueryable(
        slot: Int
    ): Boolean =
        !entityStore.hasFlag(
            slot,
            SimulatedEntityFlag.REMOVED
        ) &&
                !entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.DEAD
                )
}