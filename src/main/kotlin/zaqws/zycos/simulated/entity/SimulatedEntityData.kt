@file:Suppress("unused")

package zaqws.zycos.simulated.entity

import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedVector3

@JvmInline
value class SimulatedEntityId(val value: Int) {
    init {
        require(value > 0)
    }

    override fun toString() = value.toString()
}

@JvmInline
value class SimulatedPresentationId(val value: Int) {
    companion object {
        val NONE = SimulatedPresentationId(0)
    }

    init {
        require(value >= 0)
    }

    override fun toString() = value.toString()
}

@JvmInline
value class SimulatedTeam(val value: Int) {
    companion object {
        val NONE = SimulatedTeam(0)
    }

    init {
        require(value >= 0)
    }

    override fun toString() = value.toString()
}

data class SimulatedHitbox(
    val width: Double,
    val height: Double
) {
    companion object {
        val DEFAULT = SimulatedHitbox(1.0, 2.0)
    }

    init {
        require(width.isFinite() && width > 0.0)
        require(height.isFinite() && height > 0.0)
    }

    val halfWidth: Double
        get() = width * 0.5

    fun at(position: SimulatedVector3) =
        SimulatedAABB.fromBottomCenter(position, width, height)
}

data class SimulatedAttributes(
    val maximumHealth: Double = DEFAULT_MAXIMUM_HEALTH,
    val movementSpeed: Double = DEFAULT_MOVEMENT_SPEED,
    val attackDamage: Double = DEFAULT_ATTACK_DAMAGE,
    val attackRange: Double = DEFAULT_ATTACK_RANGE,
    val attackCooldownTicks: Int = DEFAULT_ATTACK_COOLDOWN_TICKS,
    val knockbackStrength: Double = DEFAULT_KNOCKBACK_STRENGTH
) {
    companion object {
        const val DEFAULT_MAXIMUM_HEALTH = 20.0
        const val DEFAULT_MOVEMENT_SPEED = 0.1
        const val DEFAULT_ATTACK_DAMAGE = 2.0
        const val DEFAULT_ATTACK_RANGE = 2.0
        const val DEFAULT_ATTACK_COOLDOWN_TICKS = 20
        const val DEFAULT_KNOCKBACK_STRENGTH = 0.4

        val DEFAULT = SimulatedAttributes()
    }

    init {
        require(maximumHealth.isFinite() && maximumHealth > 0.0)
        require(movementSpeed.isFinite() && movementSpeed >= 0.0)
        require(attackDamage.isFinite() && attackDamage >= 0.0)
        require(attackRange.isFinite() && attackRange >= 0.0)
        require(attackCooldownTicks >= 0)
        require(knockbackStrength.isFinite() && knockbackStrength >= 0.0)
    }
}

@JvmInline
value class SimulatedEntityFlags(val bits: Long) {
    companion object {
        val NONE = SimulatedEntityFlags(0L)

        fun of(vararg flags: SimulatedEntityFlag): SimulatedEntityFlags {
            var bits = 0L

            for (flag in flags) {
                bits = bits or flag.mask
            }

            return SimulatedEntityFlags(bits)
        }
    }

    operator fun contains(flag: SimulatedEntityFlag) = bits and flag.mask != 0L
    operator fun plus(flag: SimulatedEntityFlag) = SimulatedEntityFlags(bits or flag.mask)
    operator fun minus(flag: SimulatedEntityFlag) = SimulatedEntityFlags(bits and flag.mask.inv())

    fun with(flag: SimulatedEntityFlag, enabled: Boolean) =
        if (enabled) this + flag else this - flag

    fun containsAll(other: SimulatedEntityFlags) = bits and other.bits == other.bits
    fun containsAny(other: SimulatedEntityFlags) = bits and other.bits != 0L

    val isEmpty: Boolean
        get() = bits == 0L
}

enum class SimulatedEntityFlag(internal val mask: Long) {
    ON_GROUND(1L shl 0),
    REMOVED(1L shl 1),
    DEAD(1L shl 2),
    FULL_SIMULATION(1L shl 3),
    NO_GRAVITY(1L shl 4),
    NO_BLOCK_COLLISION(1L shl 5),
    NO_ENTITY_COLLISION(1L shl 6)
}

data class SimulatedEntitySnapshot(
    val entityId: SimulatedEntityId,
    val position: SimulatedVector3,
    val velocity: SimulatedVector3,
    val yaw: Float,
    val pitch: Float,
    val hitbox: SimulatedHitbox,
    val health: Double,
    val attributes: SimulatedAttributes,
    val team: SimulatedTeam,
    val presentationId: SimulatedPresentationId,
    val flags: SimulatedEntityFlags
) {
    val isAlive: Boolean
        get() = health > 0.0 &&
                SimulatedEntityFlag.DEAD !in flags &&
                SimulatedEntityFlag.REMOVED !in flags

    val isOnGround: Boolean
        get() = SimulatedEntityFlag.ON_GROUND in flags

    val isFullSimulation: Boolean
        get() = SimulatedEntityFlag.FULL_SIMULATION in flags
}
