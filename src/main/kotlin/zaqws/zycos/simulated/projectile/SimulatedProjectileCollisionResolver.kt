package zaqws.zycos.simulated.projectile

import zaqws.zycos.simulated.combat.SimulatedRelation
import zaqws.zycos.simulated.combat.SimulatedRelationResolver
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.external.SimulatedExternalActorId
import zaqws.zycos.simulated.external.SimulatedExternalActorSnapshot
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.spatial.SimulatedSpatialCell
import zaqws.zycos.simulated.spatial.SimulatedSpatialIndex
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal sealed interface SimulatedProjectileHit {
    val fraction: Double

    data class Block(
        override val fraction: Double
    ) : SimulatedProjectileHit

    data class Entity(
        override val fraction: Double,
        val entityId: SimulatedEntityId,
        val slot: Int
    ) : SimulatedProjectileHit

    data class ExternalActor(
        override val fraction: Double,
        val actor: SimulatedExternalActorSnapshot
    ) : SimulatedProjectileHit
}

internal class SimulatedProjectileCollisionResolver(
    private val map: SimulatedMap,
    private val entityStore: SimulatedEntityStore,
    private val spatialIndex: SimulatedSpatialIndex,
    private val relationResolver: SimulatedRelationResolver
) {
    fun find(
        source: SimulatedProjectileSource,
        team: SimulatedTeam,
        definition: SimulatedProjectileDefinition,
        start: SimulatedVector3,
        end: SimulatedVector3,
        externalFrame: SimulatedExternalFrame
    ): SimulatedProjectileHit? {
        var nearestHit:
                SimulatedProjectileHit? =
            blockHitFraction(
                start,
                end,
                definition.radius
            )?.let {
                SimulatedProjectileHit.Block(it)
            }

        nearestHit =
            nearestEntityHit(
                source,
                team,
                definition,
                start,
                end,
                nearestHit
            )

        externalFrame.forEach { actor ->
            if (
                !actor.hasCollision ||
                !canHit(
                    source,
                    team,
                    definition.targetPolicy,
                    actor.actorId,
                    actor.team
                )
            ) {
                return@forEach
            }

            val fraction =
                actor.hitbox
                    .at(actor.position)
                    .expanded(
                        definition.radius
                    )
                    .segmentIntersectionFraction(
                        start,
                        end
                    ) ?: return@forEach

            if (
                fraction <
                (nearestHit?.fraction ?: 1.0)
            ) {
                nearestHit =
                    SimulatedProjectileHit.ExternalActor(
                        fraction,
                        actor
                    )
            }
        }

        return nearestHit
    }

    private fun nearestEntityHit(
        source: SimulatedProjectileSource,
        team: SimulatedTeam,
        definition: SimulatedProjectileDefinition,
        start: SimulatedVector3,
        end: SimulatedVector3,
        initialHit: SimulatedProjectileHit?
    ): SimulatedProjectileHit? {
        val radius = definition.radius
        val maximumHalfWidth =
            spatialIndex.maximumHitboxHalfWidth
        val maximumHeight =
            spatialIndex.maximumHitboxHeight

        val minX = min(start.x, end.x) - radius - maximumHalfWidth
        val minY = min(start.y, end.y) - radius - maximumHeight
        val minZ = min(start.z, end.z) - radius - maximumHalfWidth

        val maxX = max(start.x, end.x) + radius + maximumHalfWidth
        val maxY = max(start.y, end.y) + radius
        val maxZ = max(start.z, end.z) + radius + maximumHalfWidth

        val minCellX = floor(minX / spatialIndex.cellSize).toLong()
            .coerceIn(SimulatedSpatialCell.MINIMUM_X.toLong(), SimulatedSpatialCell.MAXIMUM_X.toLong()).toInt()
        val minCellY = floor(minY / spatialIndex.cellSize).toLong()
            .coerceIn(SimulatedSpatialCell.MINIMUM_Y.toLong(), SimulatedSpatialCell.MAXIMUM_Y.toLong()).toInt()
        val minCellZ = floor(minZ / spatialIndex.cellSize).toLong()
            .coerceIn(SimulatedSpatialCell.MINIMUM_Z.toLong(), SimulatedSpatialCell.MAXIMUM_Z.toLong()).toInt()

        val maxCellX = floor(maxX / spatialIndex.cellSize).toLong()
            .coerceIn(SimulatedSpatialCell.MINIMUM_X.toLong(), SimulatedSpatialCell.MAXIMUM_X.toLong()).toInt()
        val maxCellY = floor(maxY / spatialIndex.cellSize).toLong()
            .coerceIn(SimulatedSpatialCell.MINIMUM_Y.toLong(), SimulatedSpatialCell.MAXIMUM_Y.toLong()).toInt()
        val maxCellZ = floor(maxZ / spatialIndex.cellSize).toLong()
            .coerceIn(SimulatedSpatialCell.MINIMUM_Z.toLong(), SimulatedSpatialCell.MAXIMUM_Z.toLong()).toInt()

        if (minCellX > maxCellX || minCellY > maxCellY || minCellZ > maxCellZ) {
            return initialHit
        }

        var nearestHit = initialHit

        spatialIndex.forEachCell(
            minimumCellX = minCellX,
            minimumCellY = minCellY,
            minimumCellZ = minCellZ,
            maximumCellX = maxCellX,
            maximumCellY = maxCellY,
            maximumCellZ = maxCellZ
        ) { slot ->
            if (
                entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.REMOVED
                ) ||
                entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.DEAD
                ) ||
                entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.NO_ENTITY_COLLISION
                )
            ) {
                return@forEachCell
            }

            val entityId =
                entityStore.entityIdAt(slot)

            if (
                !canHit(
                    source,
                    team,
                    definition.targetPolicy,
                    entityId,
                    entityStore.team(slot)
                )
            ) {
                return@forEachCell
            }

            val fraction =
                entityStore.hitbox(slot)
                    .at(
                        entityStore.position(slot)
                    )
                    .expanded(radius)
                    .segmentIntersectionFraction(
                        start,
                        end
                    ) ?: return@forEachCell

            if (
                fraction <
                (nearestHit?.fraction ?: 1.0)
            ) {
                nearestHit =
                    SimulatedProjectileHit.Entity(
                        fraction,
                        entityId,
                        slot
                    )
            }
        }

        return nearestHit
    }

    private fun canHit(
        source: SimulatedProjectileSource,
        sourceTeam: SimulatedTeam,
        targetPolicy: SimulatedProjectileTargetPolicy,
        entityId: SimulatedEntityId,
        targetTeam: SimulatedTeam
    ): Boolean {
        if (
            source is
            SimulatedProjectileSource.Entity &&
            source.entityId == entityId
        ) {
            return false
        }

        return canHit(
            sourceTeam,
            targetPolicy,
            targetTeam
        )
    }

    private fun canHit(
        source: SimulatedProjectileSource,
        sourceTeam: SimulatedTeam,
        targetPolicy: SimulatedProjectileTargetPolicy,
        actorId: SimulatedExternalActorId,
        targetTeam: SimulatedTeam
    ): Boolean {
        if (
            source is
            SimulatedProjectileSource.ExternalActor &&
            source.actorId == actorId
        ) {
            return false
        }

        return canHit(
            sourceTeam,
            targetPolicy,
            targetTeam
        )
    }

    private fun canHit(
        sourceTeam: SimulatedTeam,
        targetPolicy: SimulatedProjectileTargetPolicy,
        targetTeam: SimulatedTeam
    ): Boolean =
        targetPolicy ==
                SimulatedProjectileTargetPolicy.ALL_EXCEPT_SOURCE ||
                relationResolver.resolve(
                    sourceTeam,
                    targetTeam
                ) == SimulatedRelation.ENEMY

    private fun blockHitFraction(
        start: SimulatedVector3,
        end: SimulatedVector3,
        radius: Double
    ): Double? {
        if (hasBlockCollision(start, radius)) {
            return 0.0
        }

        val difference = end - start
        val interval =
            segmentInterval(
                start,
                difference,
                map.bounds.minimumX + radius,
                map.bounds.minimumY + radius,
                map.bounds.minimumZ + radius,
                map.bounds.maximumX + 1.0 - radius,
                map.bounds.maximumY + 1.0 - radius,
                map.bounds.maximumZ + 1.0 - radius
            ) ?: return null
        val intervalStart = interval.first
        val intervalEnd = interval.second
        val clippedDifference = difference * (intervalEnd - intervalStart)
        val distance = clippedDifference.length

        if (distance <= SimulatedMath.EPSILON) {
            return if (intervalEnd < 1.0) intervalEnd else null
        }

        val steps =
            ceil(
                distance /
                        MAXIMUM_BLOCK_SAMPLE_DISTANCE
            ).toInt().coerceAtLeast(1)

        var previousFraction = intervalStart
        var step = 1

        while (step <= steps) {
            val fraction =
                intervalStart +
                        (intervalEnd - intervalStart) *
                        (step.toDouble() / steps)

            if (
                hasBlockCollision(
                    start +
                            difference * fraction,
                    radius
                )
            ) {
                var minimumFraction =
                    previousFraction

                var maximumFraction =
                    fraction

                repeat(BLOCK_HIT_REFINEMENT_STEPS) {
                    val middleFraction =
                        (minimumFraction +
                                maximumFraction) * 0.5

                    if (
                        hasBlockCollision(
                            start +
                                    difference * middleFraction,
                            radius
                        )
                    ) {
                        maximumFraction =
                            middleFraction
                    } else {
                        minimumFraction =
                            middleFraction
                    }
                }

                return maximumFraction
            }

            previousFraction = fraction
            step++
        }

        return if (intervalEnd < 1.0) intervalEnd else null
    }

    private fun segmentInterval(
        start: SimulatedVector3,
        difference: SimulatedVector3,
        minimumX: Double,
        minimumY: Double,
        minimumZ: Double,
        maximumX: Double,
        maximumY: Double,
        maximumZ: Double
    ): Pair<Double, Double>? {
        var minimumFraction = 0.0
        var maximumFraction = 1.0

        fun includeAxis(
            position: Double,
            movement: Double,
            minimum: Double,
            maximum: Double
        ): Boolean {
            if (movement == 0.0) {
                return position in minimum..maximum
            }

            val first = (minimum - position) / movement
            val second = (maximum - position) / movement
            minimumFraction = max(minimumFraction, min(first, second))
            maximumFraction = min(maximumFraction, max(first, second))
            return minimumFraction <= maximumFraction
        }

        if (!includeAxis(start.x, difference.x, minimumX, maximumX)) return null
        if (!includeAxis(start.y, difference.y, minimumY, maximumY)) return null
        if (!includeAxis(start.z, difference.z, minimumZ, maximumZ)) return null

        return minimumFraction to maximumFraction
    }

    private fun hasBlockCollision(
        position: SimulatedVector3,
        radius: Double
    ): Boolean =
        map.hasCollision(
            SimulatedAABB(
                position.x - radius,
                position.y - radius,
                position.z - radius,
                position.x + radius,
                position.y + radius,
                position.z + radius
            )
        )

    companion object {
        private const val MAXIMUM_BLOCK_SAMPLE_DISTANCE = 0.25
        private const val BLOCK_HIT_REFINEMENT_STEPS = 8
    }
}
