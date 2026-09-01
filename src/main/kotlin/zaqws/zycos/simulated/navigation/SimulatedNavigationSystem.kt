package zaqws.zycos.simulated.navigation

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import zaqws.zycos.simulated.SimulatedConfig
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.goal.SimulatedGoalSystem
import zaqws.zycos.simulated.goal.SimulatedIntent
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.SimulatedMapConfig
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.paper.map.SimulatedMapRevision
import zaqws.zycos.simulated.physics.MinecraftLikeFallModel
import zaqws.zycos.simulated.physics.SimulatedFallModel
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import kotlin.math.sqrt

internal class SimulatedNavigationSystem(
    private val entityStore: SimulatedEntityStore,
    private val map: SimulatedMap,
    private val goalSystem: SimulatedGoalSystem,
    private val navigationService: SimulatedNavigationService,
    private val pathFollower: SimulatedPathFollower,
    private val config: SimulatedConfig,
    private val fallModel: SimulatedFallModel =
        MinecraftLikeFallModel()
) : SimulatedSystem {
    private data class NavigationState(
        var requestedTarget: NavigationNode? = null,
        var requestId: Long? = null,
        var lastRequestTick: Long = Long.MIN_VALUE,
        var pathRevision: SimulatedMapRevision? = null
    )

    private val navigationStates =
        Int2ObjectOpenHashMap<NavigationState>()

    private var observedMapRevision =
        map.revision

    override fun update(
        context: SimulatedSystemContext
    ) {
        refreshMapRevision()

        navigationService.drainResults(
            maximumResults =
                config.maximumPathRequestsPerTick
        ) { result ->
            consumeResult(result)
        }

        var remainingRequests =
            config.maximumPathRequestsPerTick

        var slot = 0

        while (slot < entityStore.size) {
            if (canNavigate(slot)) {
                val entityId =
                    entityStore.entityIdAt(slot)

                val requested =
                    updateEntity(
                        slot = slot,
                        entityId = entityId,
                        tick = context.tick,
                        canRequestPath =
                            remainingRequests > 0
                    )

                if (requested) {
                    remainingRequests--
                }
            }

            slot++
        }

        removeStaleState()
    }

    private fun refreshMapRevision() {
        val currentRevision =
            map.revision

        if (
            currentRevision ==
            observedMapRevision
        ) {
            return
        }

        observedMapRevision =
            currentRevision

        navigationService.invalidateCache()

        val iterator =
            navigationStates
                .int2ObjectEntrySet()
                .fastIterator()

        while (iterator.hasNext()) {
            val entry =
                iterator.next()

            val entityId =
                SimulatedEntityId(
                    entry.intKey
                )

            navigationService.cancel(
                entityId
            )

            pathFollower.clearPath(
                entityId
            )

            entry.value.requestId = null
            entry.value.pathRevision = null
        }
    }

    private fun consumeResult(
        result: SimulatedPathResult
    ) {
        val state =
            navigationStates[
                result.entityId.value
            ] ?: return

        if (
            state.requestId !=
            result.requestId
        ) {
            return
        }

        state.requestId = null

        if (
            result.mapRevision !=
            map.revision
        ) {
            state.pathRevision = null
            return
        }

        when (result) {
            is SimulatedPathResult.Success -> {
                pathFollower.setPath(
                    result.entityId,
                    result.path
                )

                state.pathRevision =
                    result.mapRevision
            }

            is SimulatedPathResult.Invalid,
            is SimulatedPathResult.Unreachable -> {
                pathFollower.clearPath(
                    result.entityId
                )

                state.pathRevision = null
            }
        }
    }

    private fun updateEntity(
        slot: Int,
        entityId: SimulatedEntityId,
        tick: Long,
        canRequestPath: Boolean
    ): Boolean {
        val movementIntent =
            goalSystem.movementIntent(
                entityId
            )

        if (
            movementIntent == null ||
            movementIntent is
                    SimulatedIntent.StopMovement
        ) {
            clearNavigation(
                slot,
                entityId
            )

            return false
        }

        val position =
            entityStore.position(slot)

        val targetPosition =
            when (movementIntent) {
                is SimulatedIntent.MoveTo -> {
                    if (
                        position
                            .horizontalDistanceSquared(
                                movementIntent.position
                            ) <=
                        movementIntent.stoppingDistance *
                        movementIntent.stoppingDistance
                    ) {
                        clearNavigation(
                            slot,
                            entityId
                        )

                        return false
                    }

                    movementIntent.position
                }

                is SimulatedIntent.MoveAwayFrom ->
                    moveAwayTarget(
                        entityId,
                        position,
                        movementIntent
                    )

                is SimulatedIntent.StopMovement ->
                    return false

                else ->
                    return false
            }

        val startNode =
            resolveNode(position)
                ?: run {
                    clearNavigation(
                        slot,
                        entityId
                    )

                    return false
                }

        val targetNode =
            resolveNode(targetPosition)
                ?: run {
                    clearNavigation(
                        slot,
                        entityId
                    )

                    return false
                }

        val state =
            navigationStates.computeIfAbsent(
                entityId.value
            ) {
                NavigationState()
            }

        val targetChanged =
            state.requestedTarget?.let {
                it.horizontalDistanceSquared(
                    targetNode
                ).toDouble() >
                        config.repathDistance *
                        config.repathDistance
            } ?: true

        val pathInvalid =
            state.pathRevision != null &&
                    state.pathRevision !=
                    map.revision

        val needsPath =
            targetChanged ||
                    pathInvalid ||
                    (
                            state.requestId == null &&
                                    !pathFollower.hasPath(
                                        entityId
                                    )
                            )

        if (
            !needsPath ||
            !canRequestPath ||
            (
                    state.lastRequestTick !=
                            Long.MIN_VALUE &&
                            tick - state.lastRequestTick <
                            config.minimumRepathIntervalTicks
                    )
        ) {
            return false
        }

        val hitbox =
            entityStore.hitbox(slot)

        val traversalProfile =
            SimulatedTraversalProfile.from(
                width = hitbox.width,
                height = hitbox.height,
                maximumStepHeight =
                    map.config.maximumStepHeight
            )

        val maximumSearchDistance =
            map.bounds.sizeY
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toDouble()

        val maximumDropHeightUnits =
            SimulatedMath.floorToInt(
                fallModel.maximumAllowedFallDistance(
                    currentHealth =
                        entityStore.health(slot),

                    maximumHealth =
                        entityStore.maximumHealth(slot),

                    maximumSearchDistance =
                        maximumSearchDistance
                ) * SimulatedMapConfig.UNITS_PER_BLOCK
            )

        val requestId =
            navigationService.requestPath(
                entityId = entityId,
                start = startNode,
                target = targetNode,
                traversalProfile =
                    traversalProfile,
                maximumDropHeightUnits =
                    maximumDropHeightUnits
            ) ?: return false

        state.requestedTarget =
            targetNode

        state.requestId =
            requestId

        state.lastRequestTick =
            tick

        return true
    }

    private fun moveAwayTarget(
        entityId: SimulatedEntityId,
        position: SimulatedVector3,
        intent: SimulatedIntent.MoveAwayFrom
    ): SimulatedVector3 {
        var differenceX =
            position.x - intent.position.x

        var differenceZ =
            position.z - intent.position.z

        var distanceSquared =
            differenceX * differenceX +
                    differenceZ * differenceZ

        if (
            distanceSquared <=
            SimulatedMath.EPSILON_SQUARED
        ) {
            if ((entityId.value and 1) == 0) {
                differenceX = 1.0
                differenceZ = 0.0
            } else {
                differenceX = 0.0
                differenceZ = 1.0
            }

            distanceSquared = 1.0
        }

        val multiplier =
            intent.distance /
                    sqrt(distanceSquared)

        return SimulatedVector3(
            x =
                intent.position.x +
                        differenceX * multiplier,

            y =
                position.y,

            z =
                intent.position.z +
                        differenceZ * multiplier
        )
    }

    private fun resolveNode(
        position: SimulatedVector3
    ): NavigationNode? {
        val worldX =
            SimulatedMath.floorToInt(
                position.x
            ).coerceIn(
                map.bounds.minimumX,
                map.bounds.maximumX
            )

        val worldZ =
            SimulatedMath.floorToInt(
                position.z
            ).coerceIn(
                map.bounds.minimumZ,
                map.bounds.maximumZ
            )

        val heightUnits =
            SimulatedMath.floorToInt(
                position.y *
                        SimulatedMapConfig.UNITS_PER_BLOCK
            )

        val surface =
            map.nearestSurface(
                worldX = worldX,
                worldZ = worldZ,
                heightUnits = heightUnits
            ) ?: return null

        return NavigationNode(
            x = worldX,
            z = worldZ,
            floorHeightUnits =
                surface.floorHeightUnits
        )
    }

    private fun clearNavigation(
        slot: Int,
        entityId: SimulatedEntityId
    ) {
        navigationService.cancel(
            entityId
        )

        pathFollower.clearPath(
            entityId
        )

        navigationStates.remove(
            entityId.value
        )

        val velocity =
            entityStore.velocity(slot)

        entityStore.setVelocity(
            slot,
            SimulatedVector3(
                x = 0.0,
                y = velocity.y,
                z = 0.0
            )
        )
    }

    private fun canNavigate(
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

    private fun removeStaleState() {
        val iterator =
            navigationStates
                .int2ObjectEntrySet()
                .fastIterator()

        while (iterator.hasNext()) {
            val entry =
                iterator.next()

            if (
                entityStore.slotOf(
                    SimulatedEntityId(
                        entry.intKey
                    )
                ) < 0
            ) {
                navigationService.cancel(
                    SimulatedEntityId(
                        entry.intKey
                    )
                )

                iterator.remove()
            }
        }
    }
}
