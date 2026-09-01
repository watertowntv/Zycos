@file:Suppress("unused")

package zaqws.zycos.simulated.map

class CollisionColumn private constructor(
    private val startY: IntArray,
    private val endY: IntArray,
    private val kinds: ByteArray
) {
    companion object {
        val EMPTY = CollisionColumn(
            IntArray(0),
            IntArray(0),
            ByteArray(0)
        )

        fun fromBlocks(
            minimumY: Int,
            kinds: Array<CollisionKind>
        ): CollisionColumn {
            if (kinds.isEmpty()) return EMPTY

            var spanCount = 0
            var index = 0

            while (index < kinds.size) {
                val kind = kinds[index]

                if (kind == CollisionKind.AIR) {
                    index++
                    continue
                }

                spanCount++

                val ordinal = kind.ordinal
                index++

                while (
                    index < kinds.size &&
                    kinds[index].ordinal == ordinal &&
                    kind.minimumHeightUnits == 0 &&
                    kind.maximumHeightUnits >=
                    SimulatedMapConfig.UNITS_PER_BLOCK
                ) {
                    index++
                }
            }

            if (spanCount == 0) return EMPTY

            val startY = IntArray(spanCount)
            val endY = IntArray(spanCount)
            val spanKinds = ByteArray(spanCount)

            var spanIndex = 0
            index = 0

            while (index < kinds.size) {
                val kind = kinds[index]

                if (kind == CollisionKind.AIR) {
                    index++
                    continue
                }

                val startIndex = index
                index++

                while (
                    index < kinds.size &&
                    kinds[index] == kind &&
                    kind.minimumHeightUnits == 0 &&
                    kind.maximumHeightUnits >=
                    SimulatedMapConfig.UNITS_PER_BLOCK
                ) {
                    index++
                }

                startY[spanIndex] =
                    minimumY + startIndex

                endY[spanIndex] =
                    minimumY + index - 1

                spanKinds[spanIndex] =
                    kind.ordinal.toByte()

                spanIndex++
            }

            return CollisionColumn(
                startY,
                endY,
                spanKinds
            )
        }

        internal fun ofSpans(
            startY: IntArray,
            endY: IntArray,
            kinds: ByteArray
        ): CollisionColumn {
            if (startY.isEmpty()) return EMPTY

            return CollisionColumn(
                startY.copyOf(),
                endY.copyOf(),
                kinds.copyOf()
            )
        }
    }

    val spanCount: Int
        get() = startY.size

    val isEmpty: Boolean
        get() = startY.isEmpty()

    init {
        require(startY.size == endY.size)
        require(startY.size == kinds.size)

        var previousEndY = Int.MIN_VALUE

        for (index in startY.indices) {
            require(startY[index] <= endY[index])
            require(startY[index] > previousEndY)

            val ordinal = kinds[index].toInt()
            require(ordinal in CollisionKind.entries.indices)
            require(CollisionKind.entries[ordinal] != CollisionKind.AIR)

            previousEndY = endY[index]
        }
    }

    fun kindAt(y: Int): CollisionKind {
        var low = 0
        var high = spanCount - 1

        while (low <= high) {
            val middle = (low + high) ushr 1

            when {
                y < startY[middle] ->
                    high = middle - 1

                y > endY[middle] ->
                    low = middle + 1

                else ->
                    return CollisionKind.entries[kinds[middle].toInt()]
            }
        }

        return CollisionKind.AIR
    }

    fun intersectsSolid(
        minimumY: Double,
        maximumY: Double
    ): Boolean {
        require(minimumY.isFinite())
        require(maximumY.isFinite())
        require(minimumY <= maximumY)

        for (index in startY.indices) {
            val kind = CollisionKind.entries[kinds[index].toInt()]

            val spanMinimumY =
                startY[index] + kind.minimumHeight
            val spanMaximumY =
                endY[index] + kind.maximumHeight

            if (
                spanMaximumY > minimumY &&
                spanMinimumY < maximumY
            ) {
                return true
            }

            if (spanMinimumY >= maximumY) {
                return false
            }
        }

        return false
    }

    private inline fun forEachSpan(
        action: (
            startY: Int,
            endY: Int,
            kind: CollisionKind
        ) -> Unit
    ) {
        var index = 0

        while (index < spanCount) {
            action(
                spanStartY(index),
                spanEndY(index),
                spanKind(index)
            )

            index++
        }
    }

    internal fun spanStartY(index: Int): Int =
        startY[index]

    internal fun spanEndY(index: Int): Int =
        endY[index]

    internal fun spanKind(index: Int): CollisionKind =
        CollisionKind.entries[kinds[index].toInt()]
}
