@file:Suppress("unused")

package zaqws.zycos.simulated.navigation

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.physics.SimulatedPhysicsConfig
import zaqws.zycos.simulated.snapshot.SimulatedEvent
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

internal class SimulatedPathFollower(
    private val entityStore: SimulatedEntityStore,
    private val physicsConfig: SimulatedPhysicsConfig =
        SimulatedPhysicsConfig.DEFAULT,
    private val eventConsumer: (SimulatedEvent) -> Unit,
    private val nodeReachDistance: Double =
        DEFAULT_NODE_REACH_DISTANCE
) : SimulatedSystem {
    companion object {
        private const val DEFAULT_NODE_REACH_DISTANCE = 0.20
        private const val DEFAULT_VERTICAL_REACH_DISTANCE = 0.20
        private const val DEFAULT_JUMP_HEIGHT_EPSILON = 0.05
    }

    private data class PathState(
        val path: SimulatedPath,
        var nodeIndex: Int
    )

    private val pathStates =
        Int2ObjectOpenHashMap<PathState>()

    init {
        require(nodeReachDistance.isFinite())
        require(nodeReachDistance > 0.0)
    }

    fun setPath(
        entityId: SimulatedEntityId,
        path: SimulatedPath
    ) {
        if (
            entityStore.slotOf(
                entityId
            ) < 0
        ) {
            return
        }

        val initialNodeIndex =
            if (path.size > 1) {
                1
            } else {
                0
            }

        pathStates.put(
            entityId.value,
            PathState(
                path = path,
                nodeIndex = initialNodeIndex
            )
        )
    }

    fun clearPath(
        entityId: SimulatedEntityId
    ) {
        pathStates.remove(
            entityId.value
        )
    }

    fun clear() {
        pathStates.clear()
    }

    fun hasPath(
        entityId: SimulatedEntityId
    ): Boolean =
        pathStates.containsKey(
            entityId.value
        )

    fun currentPath(
        entityId: SimulatedEntityId
    ): SimulatedPath? =
        pathStates[
            entityId.value
        ]?.path

    fun currentNode(
        entityId: SimulatedEntityId
    ): NavigationNode? {
        val state =
            pathStates[
                entityId.value
            ] ?: return null

        return state.path[
            state.nodeIndex
        ]
    }

    fun target(
        entityId: SimulatedEntityId
    ): NavigationNode? =
        pathStates[
            entityId.value
        ]?.path?.target

    override fun update(
        context: SimulatedSystemContext
    ) {
        val iterator =
            pathStates
                .int2ObjectEntrySet()
                .fastIterator()

        while (iterator.hasNext()) {
            val entry =
                iterator.next()

            val entityId =
                SimulatedEntityId(
                    entry.intKey
                )

            val slot =
                entityStore.slotOf(
                    entityId
                )

            if (
                slot < 0 ||
                entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.REMOVED
                ) ||
                entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.DEAD
                )
            ) {
                iterator.remove()
                continue
            }

            if (
                updatePath(
                    slot,
                    entry.value,
                    context.tick
                )
            ) {
                iterator.remove()
            }
        }
    }

    private fun updatePath(
        slot: Int,
        state: PathState,
        tick: Long
    ): Boolean {
        if (
            advanceReachedNodes(
                slot,
                state
            )
        ) {
            stopHorizontalMovement(
                slot
            )

            return true
        }

        val targetNode =
            state.path[
                state.nodeIndex
            ]

        val position =
            entityStore.position(
                slot
            )

        val targetPosition =
            targetNode.toPosition()

        val differenceX =
            targetPosition.x -
                    position.x

        val differenceZ =
            targetPosition.z -
                    position.z

        val horizontalDistanceSquared =
            differenceX * differenceX +
                    differenceZ * differenceZ

        if (
            horizontalDistanceSquared <=
            SimulatedMath.EPSILON_SQUARED
        ) {
            stopHorizontalMovement(
                slot
            )

            attemptJump(
                slot,
                targetNode,
                tick
            )

            return false
        }

        val inverseDistance =
            1.0 /
                    sqrt(
                        horizontalDistanceSquared
                    )

        val movementSpeed =
            entityStore.movementSpeed(
                slot
            )

        var velocity =
            entityStore.velocity(
                slot
            )

        velocity =
            SimulatedVector3(
                x =
                    differenceX *
                            inverseDistance *
                            movementSpeed,

                y =
                    velocity.y,

                z =
                    differenceZ *
                            inverseDistance *
                            movementSpeed
            )

        entityStore.setVelocity(
            slot,
            velocity
        )

        entityStore.setRotation(
            slot,
            calculateYaw(
                differenceX,
                differenceZ
            ),
            entityStore.pitch(slot)
        )

        attemptJump(
            slot,
            targetNode,
            tick
        )

        return false
    }

    private fun advanceReachedNodes(
        slot: Int,
        state: PathState
    ): Boolean {
        val position =
            entityStore.position(
                slot
            )

        val reachDistanceSquared =
            nodeReachDistance *
                    nodeReachDistance

        while (
            state.nodeIndex <
            state.path.size
        ) {
            val node =
                state.path[
                    state.nodeIndex
                ]

            val nodePosition =
                node.toPosition()

            val differenceX =
                nodePosition.x -
                        position.x

            val differenceZ =
                nodePosition.z -
                        position.z

            val horizontalDistanceSquared =
                differenceX * differenceX +
                        differenceZ * differenceZ

            val verticalDifference =
                kotlin.math.abs(
                    nodePosition.y -
                            position.y
                )

            if (
                horizontalDistanceSquared >
                reachDistanceSquared ||
                verticalDifference >
                DEFAULT_VERTICAL_REACH_DISTANCE
            ) {
                break
            }

            state.nodeIndex++

            if (
                state.nodeIndex >=
                state.path.size
            ) {
                return true
            }
        }

        return false
    }

    private fun attemptJump(
        slot: Int,
        targetNode: NavigationNode,
        tick: Long
    ) {
        if (
            !entityStore.hasFlag(
                slot,
                SimulatedEntityFlag.ON_GROUND
            )
        ) {
            return
        }

        val position =
            entityStore.position(
                slot
            )

        if (
            targetNode.floorHeight <=
            position.y +
            DEFAULT_JUMP_HEIGHT_EPSILON
        ) {
            return
        }

        val velocity =
            entityStore.velocity(
                slot
            )

        entityStore.setVelocity(
            slot,
            velocity.withY(
                physicsConfig.jumpVelocity
            )
        )

        entityStore.setFlag(
            slot,
            SimulatedEntityFlag.ON_GROUND,
            false
        )

        eventConsumer(
            SimulatedEvent.Jump(
                tick,
                entityStore.entityIdAt(slot)
            )
        )
    }

    private fun stopHorizontalMovement(
        slot: Int
    ) {
        val velocity =
            entityStore.velocity(
                slot
            )

        entityStore.setVelocity(
            slot,
            SimulatedVector3(
                x = 0.0,
                y = velocity.y,
                z = 0.0
            )
        )
    }

    private fun calculateYaw(
        differenceX: Double,
        differenceZ: Double
    ): Float =
        (
                atan2(
                    -differenceX,
                    differenceZ
                ) *
                        180.0 /
                        PI
                ).toFloat()
}
