@file:Suppress("unused")

package zaqws.zycos.simulated.physics

import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.spatial.SimulatedSpatialIndex
import zaqws.zycos.simulated.system.SimulatedSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class SimulatedSeparationSystem(
    private val entityStore: SimulatedEntityStore,
    private val spatialIndex: SimulatedSpatialIndex,
    private val config: SimulatedPhysicsConfig =
        SimulatedPhysicsConfig.DEFAULT,
    private val requireFullSimulation: Boolean = true
) : SimulatedSystem {
    override fun update(
        context: SimulatedSystemContext
    ) {
        if (
            entityStore.size <= 1 ||
            config.separationStrength <= 0.0
        ) {
            return
        }

        spatialIndex.rebuild(
            entityStore
        )

        var slot = 0

        while (slot < entityStore.size) {
            if (canParticipate(slot)) {
                separateEntity(
                    slot,
                    context.tick
                )
            }

            slot++
        }
    }

    private fun separateEntity(
        slot: Int,
        tick: Long
    ) {
        val position =
            entityStore.position(slot)

        val hitbox =
            entityStore.hitbox(slot)

        val searchRadius =
            hitbox.halfWidth +
                    spatialIndex.maximumHitboxHalfWidth

        spatialIndex.forEachNearby(
            position = position,
            radius = searchRadius
        ) { otherSlot ->
            if (otherSlot <= slot) {
                return@forEachNearby
            }

            if (!canParticipate(otherSlot)) {
                return@forEachNearby
            }

            separatePair(
                slot,
                otherSlot,
                tick
            )
        }
    }

    private fun separatePair(
        firstSlot: Int,
        secondSlot: Int,
        tick: Long
    ) {
        val firstPosition =
            entityStore.position(firstSlot)

        val secondPosition =
            entityStore.position(secondSlot)

        val firstHitbox =
            entityStore.hitbox(firstSlot)

        val secondHitbox =
            entityStore.hitbox(secondSlot)

        val firstMaximumY =
            firstPosition.y +
                    firstHitbox.height

        val secondMaximumY =
            secondPosition.y +
                    secondHitbox.height

        if (
            firstMaximumY <=
            secondPosition.y ||
            secondMaximumY <=
            firstPosition.y
        ) {
            return
        }

        val differenceX =
            secondPosition.x -
                    firstPosition.x

        val differenceZ =
            secondPosition.z -
                    firstPosition.z

        val requiredDistance =
            firstHitbox.halfWidth +
                    secondHitbox.halfWidth

        val absoluteDifferenceX =
            kotlin.math.abs(
                differenceX
            )

        val absoluteDifferenceZ =
            kotlin.math.abs(
                differenceZ
            )

        val overlapX =
            requiredDistance -
                    absoluteDifferenceX

        val overlapZ =
            requiredDistance -
                    absoluteDifferenceZ

        if (
            overlapX <= 0.0 ||
            overlapZ <= 0.0
        ) {
            return
        }

        val direction =
            separationDirection(
                firstSlot,
                secondSlot,
                tick,
                differenceX,
                differenceZ
            )

        val penetration =
            min(
                overlapX,
                overlapZ
            )

        val impulseMagnitude =
            min(
                config.separationStrength,
                penetration *
                        config.separationStrength
            ) / config.entityMass

        if (
            impulseMagnitude <=
            SimulatedMath.EPSILON
        ) {
            return
        }

        val impulse =
            direction *
                    impulseMagnitude

        entityStore.addVelocity(
            firstSlot,
            -impulse
        )

        entityStore.addVelocity(
            secondSlot,
            impulse
        )
    }

    private fun separationDirection(
        firstSlot: Int,
        secondSlot: Int,
        tick: Long,
        differenceX: Double,
        differenceZ: Double
    ): SimulatedVector3 {
        val lengthSquared =
            differenceX * differenceX +
                    differenceZ * differenceZ

        if (
            lengthSquared >
            SimulatedMath.EPSILON_SQUARED
        ) {
            val inverseLength =
                1.0 /
                        sqrt(lengthSquared)

            return SimulatedVector3(
                x =
                    differenceX *
                            inverseLength,

                y = 0.0,

                z =
                    differenceZ *
                            inverseLength
            )
        }

        val firstEntityId =
            entityStore
                .entityIdAt(firstSlot)
                .value

        val secondEntityId =
            entityStore
                .entityIdAt(secondSlot)
                .value

        val mixed =
            mix(
                firstEntityId,
                secondEntityId,
                tick
            )

        val normalized =
            (
                    mixed ushr 11
                    ).toDouble() /
                    (1L shl 53).toDouble()

        val angle =
            normalized *
                    PI *
                    2.0

        return SimulatedVector3(
            x = cos(angle),
            y = 0.0,
            z = sin(angle)
        )
    }

    private fun canParticipate(
        slot: Int
    ): Boolean {
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
            return false
        }

        if (
            requireFullSimulation &&
            !entityStore.hasFlag(
                slot,
                SimulatedEntityFlag.FULL_SIMULATION
            )
        ) {
            return false
        }

        return true
    }

    private fun mix(
        firstEntityId: Int,
        secondEntityId: Int,
        tick: Long
    ): Long {
        var value =
            firstEntityId.toLong() *
                    0x9E3779B97F4A7C15UL.toLong()

        value =
            value xor (
                    secondEntityId.toLong() *
                            0xC2B2AE3D27D4EB4FUL.toLong()
                    )

        value =
            value xor (
                    tick *
                            0x165667B19E3779F9L
                    )

        value =
            value xor
                    (value ushr 30)

        value *=
            0xBF58476D1CE4E5B9UL.toLong()

        value =
            value xor
                    (value ushr 27)

        value *=
            0x94D049BB133111EBUL.toLong()

        return value xor
                (value ushr 31)
    }
}