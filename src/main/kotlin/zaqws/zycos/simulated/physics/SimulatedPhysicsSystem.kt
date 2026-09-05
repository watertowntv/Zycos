package zaqws.zycos.simulated.physics

import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import kotlin.math.max

internal class SimulatedPhysicsSystem(
    private val entityStore: SimulatedEntityStore,
    map: SimulatedMap,
    private val config: SimulatedPhysicsConfig =
        SimulatedPhysicsConfig.DEFAULT,
    private val fallModel: SimulatedFallModel =
        MinecraftLikeFallModel(),
    private val damageConsumer:
        (
            entityId: SimulatedEntityId,
            damage: Double,
            tick: Long
        ) -> Unit
) : SimulatedSystem {
    private val collisionSolver =
        SimulatedCollisionSolver(
            map,
            config
        )

    private val fallDistanceByEntityId =
        Int2DoubleOpenHashMap().apply {
            defaultReturnValue(0.0)
        }

    override fun update(
        context: SimulatedSystemContext
    ) {
        var slot = 0

        while (slot < entityStore.size) {
            updateEntity(
                slot,
                context.tick
            )
            slot++
        }

        removeStaleFallDistances()
    }

    private fun updateEntity(
        slot: Int,
        tick: Long
    ) {
        if (
            entityStore.hasFlag(
                slot,
                SimulatedEntityFlag.REMOVED
            ) ||
            entityStore.hasFlag(
                slot,
                SimulatedEntityFlag.DEAD
            )
        ) {
            return
        }

        val entityId =
            entityStore.entityIdAt(slot)

        val position =
            entityStore.position(slot)

        val wasOnGround =
            entityStore.hasFlag(
                slot,
                SimulatedEntityFlag.ON_GROUND
            )

        val impulse =
            entityStore.impulseVelocity(slot)

        val steering =
            entityStore.steeringVelocity(slot)

        val requestedMovement =
            SimulatedVector3(
                impulse.x + steering.x,
                impulse.y,
                impulse.z + steering.z
            )

        val collisionResult =
            if (
                entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.NO_BLOCK_COLLISION
                )
            ) {
                null
            } else {
                collisionSolver.move(
                    entityStore.hitbox(slot)
                        .at(position),
                    requestedMovement
                )
            }

        val actualMovement =
            collisionResult?.movement
                ?: requestedMovement

        val nextPosition =
            if (collisionResult == null) {
                position + actualMovement
            } else {
                boundingBoxBottomCenter(
                    collisionResult.boundingBox
                )
            }

        val onGround =
            collisionResult != null && ((
                    collisionResult.collidedY &&
                            requestedMovement.y < 0.0
                    ) ||
                    collisionSolver.isOnGround(
                        collisionResult.boundingBox
                    ))

        updateFallDistance(
            entityIdValue = entityId.value,
            slot = slot,
            wasOnGround = wasOnGround,
            onGround = onGround,
            actualVerticalMovement =
                actualMovement.y,
            tick = tick
        )

        var newImpulseX = impulse.x
        var newImpulseY = impulse.y
        var newImpulseZ = impulse.z

        if (
            collisionResult?.collidedX == true
        ) {
            newImpulseX = 0.0
            entityStore.clearSteeringVelocity(slot)
        }

        if (
            collisionResult?.collidedY == true
        ) {
            newImpulseY = 0.0
        } else if (
            !entityStore.hasFlag(
                slot,
                SimulatedEntityFlag.NO_GRAVITY
            )
        ) {
            newImpulseY =
                max(
                    -config.maximumFallSpeed,
                    newImpulseY +
                            config.gravityPerTick
                )
        }

        if (
            collisionResult?.collidedZ == true
        ) {
            newImpulseZ = 0.0
            entityStore.clearSteeringVelocity(slot)
        }

        val draggedImpulse =
            applyDrag(
                SimulatedVector3(
                    newImpulseX,
                    newImpulseY,
                    newImpulseZ
                ),
                onGround
            )

        entityStore.setPosition(
            slot,
            nextPosition
        )

        entityStore.setVelocity(
            slot,
            draggedImpulse
        )

        entityStore.setFlag(
            slot,
            SimulatedEntityFlag.ON_GROUND,
            onGround
        )
    }

    private fun updateFallDistance(
        entityIdValue: Int,
        slot: Int,
        wasOnGround: Boolean,
        onGround: Boolean,
        actualVerticalMovement: Double,
        tick: Long
    ) {
        var fallDistance =
            fallDistanceByEntityId.get(
                entityIdValue
            )

        if (
            actualVerticalMovement < 0.0
        ) {
            fallDistance +=
                -actualVerticalMovement

            fallDistanceByEntityId.put(
                entityIdValue,
                fallDistance
            )
        }

        if (
            onGround &&
            !wasOnGround &&
            fallDistance > 0.0
        ) {
            applyFallDamage(
                slot,
                fallDistance,
                tick
            )
        }

        if (onGround) {
            fallDistanceByEntityId.remove(
                entityIdValue
            )
        } else if (
            actualVerticalMovement >= 0.0 &&
            fallDistance != 0.0
        ) {
            fallDistanceByEntityId.remove(
                entityIdValue
            )
        }
    }

    fun resetFallDistance(
        entityId: SimulatedEntityId
    ) {
        fallDistanceByEntityId.remove(
            entityId.value
        )
    }

    private fun applyFallDamage(
        slot: Int,
        fallDistance: Double,
        tick: Long
    ) {
        val damage =
            fallModel.calculateDamage(
                fallDistance
            )

        if (damage <= 0.0) {
            return
        }

        damageConsumer(
            entityStore.entityIdAt(slot),
            damage,
            tick
        )
    }

    private fun applyDrag(
        velocity: SimulatedVector3,
        onGround: Boolean
    ): SimulatedVector3 {
        val horizontalMultiplier =
            if (onGround) {
                config.groundFriction
            } else {
                config.airDrag
            }

        return SimulatedVector3(
            x =
                velocity.x *
                        horizontalMultiplier,

            y =
                velocity.y *
                        config.airDrag,

            z =
                velocity.z *
                        horizontalMultiplier
        )
    }

    private fun boundingBoxBottomCenter(
        boundingBox: zaqws.zycos.simulated.math.SimulatedAABB
    ) = SimulatedVector3(
        x =
            (
                    boundingBox.minimumX +
                            boundingBox.maximumX
                    ) * 0.5,

        y =
            boundingBox.minimumY,

        z =
            (
                    boundingBox.minimumZ +
                            boundingBox.maximumZ
                    ) * 0.5
    )

    private fun removeStaleFallDistances() {
        val iterator =
            fallDistanceByEntityId
                .int2DoubleEntrySet()
                .fastIterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()

            if (
                entityStore.slotOf(
                    SimulatedEntityId(
                        entry.intKey
                    )
                ) < 0
            ) {
                iterator.remove()
            }
        }
    }
}
