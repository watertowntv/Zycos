@file:Suppress("unused")

package zaqws.zycos.simulated.entity

@JvmInline
value class SimulatedEntityFlags(
    val bits: Long
) {
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

    operator fun contains(flag: SimulatedEntityFlag): Boolean =
        bits and flag.mask != 0L

    operator fun plus(flag: SimulatedEntityFlag) =
        SimulatedEntityFlags(bits or flag.mask)

    operator fun minus(flag: SimulatedEntityFlag) =
        SimulatedEntityFlags(bits and flag.mask.inv())

    fun with(
        flag: SimulatedEntityFlag,
        enabled: Boolean
    ) = if (enabled) this + flag else this - flag

    fun containsAll(other: SimulatedEntityFlags): Boolean =
        bits and other.bits == other.bits

    fun containsAny(other: SimulatedEntityFlags): Boolean =
        bits and other.bits != 0L

    val isEmpty: Boolean
        get() = bits == 0L
}

enum class SimulatedEntityFlag(
    internal val mask: Long
) {
    ON_GROUND(1L shl 0),
    REMOVED(1L shl 1),
    DEAD(1L shl 2),
    FULL_SIMULATION(1L shl 3),
    NO_GRAVITY(1L shl 4),
    NO_BLOCK_COLLISION(1L shl 5),
    NO_ENTITY_COLLISION(1L shl 6)
}