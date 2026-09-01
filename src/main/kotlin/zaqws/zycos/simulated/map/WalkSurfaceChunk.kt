package zaqws.zycos.simulated.map

import zaqws.zycos.simulated.math.SimulatedBlockPosition

class WalkSurfaceChunk internal constructor(
    val chunkX: Int,
    val chunkZ: Int,
    columns: Array<List<WalkSurface>>
) {
    companion object {
        const val CHUNK_SIZE =
            SimulatedBlockPosition.CHUNK_SIZE

        const val COLUMN_COUNT =
            CHUNK_SIZE * CHUNK_SIZE

        internal fun empty(
            chunkX: Int,
            chunkZ: Int
        ) = WalkSurfaceChunk(
            chunkX = chunkX,
            chunkZ = chunkZ,
            columns = Array(COLUMN_COUNT) {
                emptyList()
            }
        )
    }

    private val columnOffsets =
        IntArray(COLUMN_COUNT + 1)

    private val floorHeightUnits: IntArray
    private val ceilingHeightUnits: IntArray
    private val supportKinds: ByteArray
    private val waterDepthUnits: IntArray

    val surfaceCount: Int
        get() = floorHeightUnits.size

    val isEmpty: Boolean
        get() = surfaceCount == 0

    init {
        require(columns.size == COLUMN_COUNT)

        var totalSurfaceCount = 0

        for (columnIndex in columns.indices) {
            columnOffsets[columnIndex] =
                totalSurfaceCount

            val column = columns[columnIndex]

            var previousFloorHeight =
                Int.MIN_VALUE

            for ((floorHeightUnits) in column) {
                require(
                    floorHeightUnits >
                            previousFloorHeight
                )

                previousFloorHeight =
                    floorHeightUnits
            }

            totalSurfaceCount +=
                column.size
        }

        columnOffsets[COLUMN_COUNT] =
            totalSurfaceCount

        floorHeightUnits =
            IntArray(totalSurfaceCount)

        ceilingHeightUnits =
            IntArray(totalSurfaceCount)

        supportKinds =
            ByteArray(totalSurfaceCount)

        waterDepthUnits =
            IntArray(totalSurfaceCount)

        var destinationIndex = 0

        for (column in columns) {
            for ((floorHeightUnits, ceilingHeightUnits, supportKind, waterDepthUnits) in column) {
                this.floorHeightUnits[destinationIndex] =
                    floorHeightUnits

                this.ceilingHeightUnits[destinationIndex] =
                    ceilingHeightUnits

                this.supportKinds[destinationIndex] =
                    supportKind.ordinal.toByte()

                this.waterDepthUnits[destinationIndex] =
                    waterDepthUnits

                destinationIndex++
            }
        }
    }

    fun surfaceCountAt(
        localX: Int,
        localZ: Int
    ): Int {
        requireValidLocalCoordinate(
            localX,
            localZ
        )

        val columnIndex =
            columnIndex(localX, localZ)

        return columnOffsets[columnIndex + 1] -
                columnOffsets[columnIndex]
    }

    fun surfaceAt(
        localX: Int,
        localZ: Int,
        surfaceIndex: Int
    ): WalkSurface {
        requireValidLocalCoordinate(
            localX,
            localZ
        )

        val columnIndex =
            columnIndex(localX, localZ)

        val startIndex =
            columnOffsets[columnIndex]

        val endIndex =
            columnOffsets[columnIndex + 1]

        require(
            surfaceIndex in
                    0 until endIndex - startIndex
        )

        return surfaceAtAbsoluteIndex(
            startIndex + surfaceIndex
        )
    }

    fun nearestSurface(
        localX: Int,
        localZ: Int,
        heightUnits: Int,
        maximumDifferenceUnits: Int = Int.MAX_VALUE
    ): WalkSurface? {
        requireValidLocalCoordinate(
            localX,
            localZ
        )

        require(maximumDifferenceUnits >= 0)

        val columnIndex =
            columnIndex(localX, localZ)

        val startIndex =
            columnOffsets[columnIndex]

        val endIndex =
            columnOffsets[columnIndex + 1]

        if (startIndex == endIndex) {
            return null
        }

        var bestIndex = -1
        var bestDifference = Int.MAX_VALUE

        var index = startIndex

        while (index < endIndex) {
            val difference =
                kotlin.math.abs(
                    floorHeightUnits[index] -
                            heightUnits
                )

            if (
                difference < bestDifference &&
                difference <= maximumDifferenceUnits
            ) {
                bestDifference = difference
                bestIndex = index
            }

            if (
                floorHeightUnits[index] >
                heightUnits &&
                difference > bestDifference
            ) {
                break
            }

            index++
        }

        return if (bestIndex >= 0) {
            surfaceAtAbsoluteIndex(bestIndex)
        } else {
            null
        }
    }

    fun highestSurfaceAtOrBelow(
        localX: Int,
        localZ: Int,
        maximumHeightUnits: Int
    ): WalkSurface? {
        requireValidLocalCoordinate(
            localX,
            localZ
        )

        val columnIndex =
            columnIndex(localX, localZ)

        val startIndex =
            columnOffsets[columnIndex]

        var low = startIndex
        var high =
            columnOffsets[columnIndex + 1] - 1

        var resultIndex = -1

        while (low <= high) {
            val middle = (low + high) ushr 1

            if (
                floorHeightUnits[middle] <=
                maximumHeightUnits
            ) {
                resultIndex = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        return if (resultIndex >= 0) {
            surfaceAtAbsoluteIndex(resultIndex)
        } else {
            null
        }
    }

    fun containsWorldPosition(
        worldX: Int,
        worldZ: Int
    ): Boolean =
        worldX shr SimulatedBlockPosition.CHUNK_SHIFT == chunkX &&
                worldZ shr SimulatedBlockPosition.CHUNK_SHIFT == chunkZ

    internal fun absoluteSurfaceIndex(
        localX: Int,
        localZ: Int,
        surfaceIndex: Int
    ): Int {
        requireValidLocalCoordinate(
            localX,
            localZ
        )

        val columnIndex =
            columnIndex(localX, localZ)

        val startIndex =
            columnOffsets[columnIndex]

        val endIndex =
            columnOffsets[columnIndex + 1]

        require(
            surfaceIndex in
                    0 until endIndex - startIndex
        )

        return startIndex + surfaceIndex
    }

    internal fun floorHeightUnitsAtAbsoluteIndex(
        index: Int
    ): Int =
        floorHeightUnits[index]

    internal fun ceilingHeightUnitsAtAbsoluteIndex(
        index: Int
    ): Int =
        ceilingHeightUnits[index]

    private fun surfaceAtAbsoluteIndex(
        index: Int
    ) = WalkSurface(
        floorHeightUnits =
            floorHeightUnits[index],

        ceilingHeightUnits =
            ceilingHeightUnits[index],

        supportKind =
            CollisionKind.entries[
                supportKinds[index].toInt()
            ],

        waterDepthUnits =
            waterDepthUnits[index]
    )

    private fun columnIndex(
        localX: Int,
        localZ: Int
    ): Int =
        localZ * CHUNK_SIZE + localX

    private fun requireValidLocalCoordinate(
        localX: Int,
        localZ: Int
    ) {
        require(localX in 0 until CHUNK_SIZE)
        require(localZ in 0 until CHUNK_SIZE)
    }
}
