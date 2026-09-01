@file:Suppress("unused")

package zaqws.zycos.simulated.external

import zaqws.zycos.simulated.entity.SimulatedHitbox
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.math.SimulatedVector3

@JvmInline
value class SimulatedExternalActorId(val value: Long) {
    init {
        require(value > 0L)
    }

    override fun toString() = value.toString()
}

interface SimulatedExternalActorProvider {
    fun capture(sequence: Long): SimulatedExternalFrame
}

data class SimulatedExternalActorSnapshot(
    val actorId: SimulatedExternalActorId,
    val position: SimulatedVector3,
    val velocity: SimulatedVector3,
    val hitbox: SimulatedHitbox,
    val health: Double,
    val maximumHealth: Double,
    val team: SimulatedTeam = SimulatedTeam.NONE,
    val flags: SimulatedExternalActorFlags =
        SimulatedExternalActorFlags.DEFAULT
) {
    init {
        require(position.isFinite)
        require(velocity.isFinite)

        require(health.isFinite())
        require(maximumHealth.isFinite())

        require(maximumHealth > 0.0)
        require(health in 0.0..maximumHealth)
    }

    val isAlive: Boolean
        get() =
            health > 0.0 &&
                    SimulatedExternalActorFlag.ALIVE in flags

    val isTargetable: Boolean
        get() =
            isAlive &&
                    SimulatedExternalActorFlag.TARGETABLE in flags

    val isDamageable: Boolean
        get() =
            isAlive &&
                    SimulatedExternalActorFlag.DAMAGEABLE in flags

    val hasCollision: Boolean
        get() =
            SimulatedExternalActorFlag.COLLIDABLE in flags
}

@JvmInline
value class SimulatedExternalActorFlags(
    val bits: Long
) {
    operator fun contains(
        flag: SimulatedExternalActorFlag
    ): Boolean =
        bits and flag.mask != 0L

    operator fun plus(
        flag: SimulatedExternalActorFlag
    ) = SimulatedExternalActorFlags(
        bits or flag.mask
    )

    operator fun minus(
        flag: SimulatedExternalActorFlag
    ) = SimulatedExternalActorFlags(
        bits and flag.mask.inv()
    )

    fun with(
        flag: SimulatedExternalActorFlag,
        enabled: Boolean
    ) =
        if (enabled) {
            this + flag
        } else {
            this - flag
        }

    companion object {
        val NONE =
            SimulatedExternalActorFlags(0L)

        val DEFAULT =
            of(
                SimulatedExternalActorFlag.ALIVE,
                SimulatedExternalActorFlag.TARGETABLE,
                SimulatedExternalActorFlag.DAMAGEABLE,
                SimulatedExternalActorFlag.COLLIDABLE
            )

        fun of(
            vararg flags: SimulatedExternalActorFlag
        ): SimulatedExternalActorFlags {
            var bits = 0L

            for (flag in flags) {
                bits =
                    bits or flag.mask
            }

            return SimulatedExternalActorFlags(
                bits
            )
        }
    }
}

enum class SimulatedExternalActorFlag(
    internal val mask: Long
) {
    ALIVE(1L shl 0),
    TARGETABLE(1L shl 1),
    DAMAGEABLE(1L shl 2),
    COLLIDABLE(1L shl 3),
    ON_GROUND(1L shl 4)
}
