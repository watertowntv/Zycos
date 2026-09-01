@file:Suppress("unused")

package zaqws.zycos.simulated.map

import zaqws.zycos.simulated.math.SimulatedBlockPosition
import zaqws.zycos.simulated.math.SimulatedVector3
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class SimulatedBounds(
    val minimumX: Int,
    val minimumY: Int,
    val minimumZ: Int,
    val maximumX: Int,
    val maximumY: Int,
    val maximumZ: Int
) {
    companion object {
        fun between(
            first: SimulatedBlockPosition,
            second: SimulatedBlockPosition
        ) = SimulatedBounds(
            minimumX = min(first.x, second.x),
            minimumY = min(first.y, second.y),
            minimumZ = min(first.z, second.z),
            maximumX = max(first.x, second.x),
            maximumY = max(first.y, second.y),
            maximumZ = max(first.z, second.z)
        )
    }

    init {
        require(minimumX <= maximumX)
        require(minimumY <= maximumY)
        require(minimumZ <= maximumZ)

        require(minimumX >= SimulatedBlockPosition.MINIMUM_X)
        require(maximumX <= SimulatedBlockPosition.MAXIMUM_X)

        require(minimumY >= SimulatedBlockPosition.MINIMUM_Y)
        require(maximumY <= SimulatedBlockPosition.MAXIMUM_Y)

        require(minimumZ >= SimulatedBlockPosition.MINIMUM_Z)
        require(maximumZ <= SimulatedBlockPosition.MAXIMUM_Z)
    }

    val sizeX: Long
        get() = maximumX.toLong() - minimumX + 1L

    val sizeY: Long
        get() = maximumY.toLong() - minimumY + 1L

    val sizeZ: Long
        get() = maximumZ.toLong() - minimumZ + 1L

    val volume: Long
        get() {
            val horizontalSize = Math.multiplyExact(
                sizeX,
                sizeZ
            )

            return Math.multiplyExact(
                horizontalSize,
                sizeY
            )
        }

    val minimumChunkX: Int
        get() = minimumX shr SimulatedBlockPosition.CHUNK_SHIFT

    val maximumChunkX: Int
        get() = maximumX shr SimulatedBlockPosition.CHUNK_SHIFT

    val minimumChunkZ: Int
        get() = minimumZ shr SimulatedBlockPosition.CHUNK_SHIFT

    val maximumChunkZ: Int
        get() = maximumZ shr SimulatedBlockPosition.CHUNK_SHIFT

    val chunkCountX: Long
        get() = maximumChunkX.toLong() - minimumChunkX + 1L

    val chunkCountZ: Long
        get() = maximumChunkZ.toLong() - minimumChunkZ + 1L

    val chunkCount: Long
        get() = Math.multiplyExact(
            chunkCountX,
            chunkCountZ
        )

    val center: SimulatedVector3
        get() = SimulatedVector3(
            (minimumX.toDouble() + maximumX + 1.0) * 0.5,
            (minimumY.toDouble() + maximumY + 1.0) * 0.5,
            (minimumZ.toDouble() + maximumZ + 1.0) * 0.5
        )

    operator fun contains(
        position: SimulatedBlockPosition
    ): Boolean =
        position.x in minimumX..maximumX &&
                position.y in minimumY..maximumY &&
                position.z in minimumZ..maximumZ

    operator fun contains(
        position: SimulatedVector3
    ): Boolean {
        return position.isFinite && floor(position.x) >= minimumX &&
                position.x < maximumX + 1.0 &&
                floor(position.y) >= minimumY &&
                position.y < maximumY + 1.0 &&
                floor(position.z) >= minimumZ &&
                position.z < maximumZ + 1.0
    }

    fun contains(
        x: Int,
        y: Int,
        z: Int
    ): Boolean =
        x in minimumX..maximumX &&
                y in minimumY..maximumY &&
                z in minimumZ..maximumZ

    fun containsChunk(
        chunkX: Int,
        chunkZ: Int
    ): Boolean =
        chunkX in minimumChunkX..maximumChunkX &&
                chunkZ in minimumChunkZ..maximumChunkZ

    fun clamp(
        position: SimulatedBlockPosition
    ) = SimulatedBlockPosition.of(
        position.x.coerceIn(
            minimumX,
            maximumX
        ),
        position.y.coerceIn(
            minimumY,
            maximumY
        ),
        position.z.coerceIn(
            minimumZ,
            maximumZ
        )
    )

    fun intersects(
        other: SimulatedBounds
    ): Boolean =
        minimumX <= other.maximumX &&
                maximumX >= other.minimumX &&
                minimumY <= other.maximumY &&
                maximumY >= other.minimumY &&
                minimumZ <= other.maximumZ &&
                maximumZ >= other.minimumZ

    fun intersection(
        other: SimulatedBounds
    ): SimulatedBounds? {
        if (!intersects(other)) return null

        return SimulatedBounds(
            minimumX = max(minimumX, other.minimumX),
            minimumY = max(minimumY, other.minimumY),
            minimumZ = max(minimumZ, other.minimumZ),
            maximumX = min(maximumX, other.maximumX),
            maximumY = min(maximumY, other.maximumY),
            maximumZ = min(maximumZ, other.maximumZ)
        )
    }
}