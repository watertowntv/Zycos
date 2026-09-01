@file:Suppress("unused")

package zaqws.zycos.simulated.map

import zaqws.zycos.simulated.math.SimulatedBlockPosition

class BakedChunk internal constructor(
    val chunkX: Int,
    val chunkZ: Int,
    val minimumY: Int,
    val maximumY: Int,
    columns: Array<CollisionColumn>
) {
    companion object {
        const val CHUNK_SIZE =
            SimulatedBlockPosition.CHUNK_SIZE

        const val COLUMN_COUNT =
            CHUNK_SIZE * CHUNK_SIZE

        internal fun empty(
            chunkX: Int,
            chunkZ: Int,
            minimumY: Int,
            maximumY: Int
        ) = BakedChunk(
            chunkX = chunkX,
            chunkZ = chunkZ,
            minimumY = minimumY,
            maximumY = maximumY,
            columns = Array(COLUMN_COUNT) {
                CollisionColumn.EMPTY
            }
        )
    }

    private val columnOffsets =
        IntArray(COLUMN_COUNT + 1)

    private val spanStartY: IntArray
    private val spanEndY: IntArray
    private val spanKinds: ByteArray

    val spanCount: Int
        get() = spanStartY.size

    val isEmpty: Boolean
        get() = spanStartY.isEmpty()

    init {
        require(minimumY <= maximumY)
        require(columns.size == COLUMN_COUNT)

        var totalSpanCount = 0

        for (columnIndex in columns.indices) {
            columnOffsets[columnIndex] =
                totalSpanCount

            totalSpanCount +=
                columns[columnIndex].spanCount
        }

        columnOffsets[COLUMN_COUNT] =
            totalSpanCount

        spanStartY = IntArray(totalSpanCount)
        spanEndY = IntArray(totalSpanCount)
        spanKinds = ByteArray(totalSpanCount)

        var destinationIndex = 0

        for (column in columns) {
            var spanIndex = 0

            while (spanIndex < column.spanCount) {
                val startY =
                    column.spanStartY(spanIndex)

                val endY =
                    column.spanEndY(spanIndex)

                require(startY >= minimumY)
                require(endY <= maximumY)

                spanStartY[destinationIndex] =
                    startY

                spanEndY[destinationIndex] =
                    endY

                spanKinds[destinationIndex] =
                    column.spanKind(spanIndex)
                        .ordinal
                        .toByte()

                destinationIndex++
                spanIndex++
            }
        }
    }

    fun collisionKindAt(
        worldX: Int,
        y: Int,
        worldZ: Int
    ): CollisionKind {
        if (y !in minimumY..maximumY) {
            return CollisionKind.AIR
        }

        if (
            worldX shr SimulatedBlockPosition.CHUNK_SHIFT != chunkX ||
            worldZ shr SimulatedBlockPosition.CHUNK_SHIFT != chunkZ
        ) {
            return CollisionKind.AIR
        }

        return collisionKindAtLocal(
            worldX and SimulatedBlockPosition.CHUNK_MASK,
            y,
            worldZ and SimulatedBlockPosition.CHUNK_MASK
        )
    }

    fun collisionKindAtLocal(
        localX: Int,
        y: Int,
        localZ: Int
    ): CollisionKind {
        requireValidLocalCoordinate(
            localX,
            localZ
        )

        if (y !in minimumY..maximumY) {
            return CollisionKind.AIR
        }

        val columnIndex =
            columnIndex(localX, localZ)

        var low =
            columnOffsets[columnIndex]

        var high =
            columnOffsets[columnIndex + 1] - 1

        while (low <= high) {
            val middle = (low + high) ushr 1

            when {
                y < spanStartY[middle] ->
                    high = middle - 1

                y > spanEndY[middle] ->
                    low = middle + 1

                else ->
                    return CollisionKind.entries[
                        spanKinds[middle].toInt()
                    ]
            }
        }

        return CollisionKind.AIR
    }

    fun hasCollision(
        localX: Int,
        localZ: Int,
        minimumY: Double,
        maximumY: Double
    ): Boolean {
        requireValidLocalCoordinate(
            localX,
            localZ
        )

        require(minimumY.isFinite())
        require(maximumY.isFinite())
        require(minimumY <= maximumY)

        val columnIndex =
            columnIndex(localX, localZ)

        val startIndex =
            columnOffsets[columnIndex]

        val endIndex =
            columnOffsets[columnIndex + 1]

        var index = startIndex

        while (index < endIndex) {
            val kind = CollisionKind.entries[
                spanKinds[index].toInt()
            ]

            val collisionMinimumY =
                spanStartY[index] + kind.minimumHeight

            val collisionMaximumY =
                spanEndY[index] + kind.maximumHeight

            if (
                collisionMaximumY > minimumY &&
                collisionMinimumY < maximumY
            ) {
                return true
            }

            if (collisionMinimumY >= maximumY) {
                return false
            }

            index++
        }

        return false
    }

    fun contains(
        worldX: Int,
        y: Int,
        worldZ: Int
    ): Boolean =
        y in minimumY..maximumY &&
                worldX shr SimulatedBlockPosition.CHUNK_SHIFT == chunkX &&
                worldZ shr SimulatedBlockPosition.CHUNK_SHIFT == chunkZ

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
