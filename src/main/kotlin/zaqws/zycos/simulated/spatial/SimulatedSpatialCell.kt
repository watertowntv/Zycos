package zaqws.zycos.simulated.spatial

import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3

data class SimulatedSpatialCell(
    val x: Int,
    val y: Int,
    val z: Int
) {
    companion object {
        fun from(
            position: SimulatedVector3,
            cellSize: Double
        ): SimulatedSpatialCell {
            require(position.isFinite)
            require(cellSize.isFinite())
            require(cellSize > 0.0)

            return SimulatedSpatialCell(
                x = SimulatedMath.floorToInt(
                    position.x / cellSize
                ),
                y = SimulatedMath.floorToInt(
                    position.y / cellSize
                ),
                z = SimulatedMath.floorToInt(
                    position.z / cellSize
                )
            )
        }

        internal fun pack(
            x: Int,
            y: Int,
            z: Int
        ): Long {
            require(x in MINIMUM_X..MAXIMUM_X)
            require(y in MINIMUM_Y..MAXIMUM_Y)
            require(z in MINIMUM_Z..MAXIMUM_Z)

            return ((x.toLong() and X_MASK) shl X_SHIFT) or
                    ((z.toLong() and Z_MASK) shl Y_BITS) or
                    (y.toLong() and Y_MASK)
        }

        internal fun pack(
            cell: SimulatedSpatialCell
        ): Long =
            pack(
                cell.x,
                cell.y,
                cell.z
            )

        fun isValidPosition(
            position: SimulatedVector3,
            cellSize: Double
        ): Boolean {
            if (!position.isFinite || !cellSize.isFinite() || cellSize <= 0.0) return false
            val cellX = SimulatedMath.floorToInt(position.x / cellSize)
            val cellY = SimulatedMath.floorToInt(position.y / cellSize)
            val cellZ = SimulatedMath.floorToInt(position.z / cellSize)
            return cellX in MINIMUM_X..MAXIMUM_X &&
                    cellY in MINIMUM_Y..MAXIMUM_Y &&
                    cellZ in MINIMUM_Z..MAXIMUM_Z
        }

        internal const val X_BITS = 24
        internal const val Y_BITS = 16
        internal const val Z_BITS = 24

        private const val Y_MASK =
            (1L shl Y_BITS) - 1L

        private const val Z_MASK =
            (1L shl Z_BITS) - 1L

        private const val X_MASK =
            (1L shl X_BITS) - 1L

        private const val X_SHIFT =
            Y_BITS + Z_BITS

        internal const val MINIMUM_X =
            -(1 shl (X_BITS - 1))

        internal const val MAXIMUM_X =
            (1 shl (X_BITS - 1)) - 1

        internal const val MINIMUM_Y =
            -(1 shl (Y_BITS - 1))

        internal const val MAXIMUM_Y =
            (1 shl (Y_BITS - 1)) - 1

        internal const val MINIMUM_Z =
            MINIMUM_X

        internal const val MAXIMUM_Z =
            MAXIMUM_X
    }
}