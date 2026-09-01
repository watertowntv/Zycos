@file:Suppress("unused")

package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.map.SimulatedMapConfig
import zaqws.zycos.simulated.math.SimulatedBlockPosition
import zaqws.zycos.simulated.math.SimulatedVector3

data class NavigationNode(
    val x: Int,
    val z: Int,
    val floorHeightUnits: Int
) {
    val floorHeight: Double
        get() =
            floorHeightUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val chunkX: Int
        get() =
            x shr
                    SimulatedBlockPosition
                        .CHUNK_SHIFT

    val chunkZ: Int
        get() =
            z shr
                    SimulatedBlockPosition
                        .CHUNK_SHIFT

    val localX: Int
        get() =
            x and
                    SimulatedBlockPosition
                        .CHUNK_MASK

    val localZ: Int
        get() =
            z and
                    SimulatedBlockPosition
                        .CHUNK_MASK

    fun toPosition() =
        SimulatedVector3(
            x = x + 0.5,
            y = floorHeight,
            z = z + 0.5
        )

    fun horizontalDistanceSquared(
        other: NavigationNode
    ): Long {
        val differenceX =
            x.toLong() - other.x

        val differenceZ =
            z.toLong() - other.z

        return differenceX * differenceX +
                differenceZ * differenceZ
    }

    fun verticalDifferenceUnits(
        other: NavigationNode
    ): Int =
        other.floorHeightUnits -
                floorHeightUnits
}
