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

        internal fun unpackX(packed: Long): Int {
            val rawX = (packed ushr X_SHIFT).toInt()
            return (rawX shl 8) shr 8
        }

        internal fun unpackY(packed: Long): Int {
            val rawY = (packed and Y_MASK).toInt()
            return (rawY shl 16) shr 16
        }

        internal fun unpackZ(packed: Long): Int {
            val rawZ = ((packed ushr Y_BITS) and Z_MASK).toInt()
            return (rawZ shl 8) shr 8
        }

        fun isValidPosition(
            position: SimulatedVector3,
            cellSize: Double
        ): Boolean {
            if (!position.isFinite || !cellSize.isFinite() || cellSize <= 0.0) return false
            val minX = MINIMUM_X.toDouble() * cellSize
            val maxX = (MAXIMUM_X.toDouble() + 1.0) * cellSize
            val minY = MINIMUM_Y.toDouble() * cellSize
            val maxY = (MAXIMUM_Y.toDouble() + 1.0) * cellSize
            val minZ = MINIMUM_Z.toDouble() * cellSize
            val maxZ = (MAXIMUM_Z.toDouble() + 1.0) * cellSize

            return position.x >= minX && position.x < maxX &&
                    position.y >= minY && position.y < maxY &&
                    position.z >= minZ && position.z < maxZ
        }

        fun clampPosition(
            position: SimulatedVector3,
            cellSize: Double
        ): SimulatedVector3 {
            val minX = MINIMUM_X.toDouble() * cellSize
            val maxX = Math.nextDown((MAXIMUM_X.toDouble() + 1.0) * cellSize)
            val minY = MINIMUM_Y.toDouble() * cellSize
            val maxY = Math.nextDown((MAXIMUM_Y.toDouble() + 1.0) * cellSize)
            val minZ = MINIMUM_Z.toDouble() * cellSize
            val maxZ = Math.nextDown((MAXIMUM_Z.toDouble() + 1.0) * cellSize)

            return SimulatedVector3(
                position.x.coerceIn(minX, maxX),
                position.y.coerceIn(minY, maxY),
                position.z.coerceIn(minZ, maxZ)
            )
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