@file:Suppress("unused")

package zaqws.zycos.simulated.math

import kotlin.math.floor

@JvmInline
value class SimulatedBlockPosition(
    val packed: Long
) {
    val x: Int
        get() = (packed shr X_SHIFT).toInt()

    val y: Int
        get() = (packed shl Y_SIGN_SHIFT shr Y_SIGN_SHIFT).toInt()

    val z: Int
        get() = (packed shl Z_SIGN_LEFT_SHIFT shr Z_SIGN_RIGHT_SHIFT).toInt()

    val chunkX: Int
        get() = x shr CHUNK_SHIFT

    val chunkZ: Int
        get() = z shr CHUNK_SHIFT

    val localX: Int
        get() = x and CHUNK_MASK

    val localZ: Int
        get() = z and CHUNK_MASK

    operator fun plus(other: SimulatedBlockPosition) = of(
        x + other.x,
        y + other.y,
        z + other.z
    )

    operator fun minus(other: SimulatedBlockPosition) = of(
        x - other.x,
        y - other.y,
        z - other.z
    )

    fun shifted(
        x: Int = 0,
        y: Int = 0,
        z: Int = 0
    ) = of(
        this.x + x,
        this.y + y,
        this.z + z
    )

    fun toVector3() = SimulatedVector3(
        x.toDouble(),
        y.toDouble(),
        z.toDouble()
    )

    fun toCenterVector3() = SimulatedVector3(
        x + 0.5,
        y.toDouble(),
        z + 0.5
    )

    override fun toString() =
        "SimulatedBlockPosition(x=$x, y=$y, z=$z)"

    companion object {
        const val CHUNK_SHIFT = 4
        const val CHUNK_SIZE = 1 shl CHUNK_SHIFT
        const val CHUNK_MASK = CHUNK_SIZE - 1

        const val MINIMUM_X = -33_554_432
        const val MAXIMUM_X = 33_554_431

        const val MINIMUM_Y = -2_048
        const val MAXIMUM_Y = 2_047

        const val MINIMUM_Z = MINIMUM_X
        const val MAXIMUM_Z = MAXIMUM_X

        private const val X_BITS = 26
        private const val Y_BITS = 12
        private const val Z_BITS = 26

        private const val Y_MASK = (1L shl Y_BITS) - 1L
        private const val Z_MASK = (1L shl Z_BITS) - 1L
        private const val X_MASK = (1L shl X_BITS) - 1L

        private const val X_SHIFT = Y_BITS + Z_BITS

        private const val Y_SIGN_SHIFT = Long.SIZE_BITS - Y_BITS
        private const val Z_SIGN_LEFT_SHIFT = Long.SIZE_BITS - Z_BITS - Y_BITS
        private const val Z_SIGN_RIGHT_SHIFT = Long.SIZE_BITS - Z_BITS

        fun of(
            x: Int,
            y: Int,
            z: Int
        ): SimulatedBlockPosition {
            require(x in MINIMUM_X..MAXIMUM_X)
            require(y in MINIMUM_Y..MAXIMUM_Y)
            require(z in MINIMUM_Z..MAXIMUM_Z)

            return SimulatedBlockPosition(packUnchecked(x, y, z))
        }

        fun from(position: SimulatedVector3) = of(
            floor(position.x).toInt(),
            floor(position.y).toInt(),
            floor(position.z).toInt()
        )

        internal fun packUnchecked(
            x: Int,
            y: Int,
            z: Int
        ): Long =
            ((x.toLong() and X_MASK) shl X_SHIFT) or
                    ((z.toLong() and Z_MASK) shl Y_BITS) or
                    (y.toLong() and Y_MASK)
    }
}
