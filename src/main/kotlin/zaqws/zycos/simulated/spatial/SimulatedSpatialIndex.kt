@file:Suppress("unused")

package zaqws.zycos.simulated.spatial

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.math.SimulatedVector3
import kotlin.math.ceil
import kotlin.math.floor

internal class SimulatedSpatialIndex(
    val cellSize: Double
) {
    private val cells =
        Long2ObjectOpenHashMap<IntArrayList>()

    private var indexedEntityCount = 0

    var maximumHitboxHalfWidth: Double = 0.0
        private set

    var maximumHitboxHeight: Double = 0.0
        private set

    init {
        require(cellSize.isFinite())
        require(cellSize > 0.0)
    }

    val size: Int
        get() = indexedEntityCount

    val cellCount: Int
        get() = cells.size

    fun rebuild(
        entityStore: SimulatedEntityStore
    ) {
        cells.clear()

        indexedEntityCount = 0
        maximumHitboxHalfWidth = 0.0
        maximumHitboxHeight = 0.0

        var slot = 0

        while (slot < entityStore.size) {
            if (
                !entityStore.hasFlag(
                    slot,
                    SimulatedEntityFlag.REMOVED
                )
            ) {
                add(
                    slot,
                    entityStore.position(slot)
                )

                maximumHitboxHalfWidth =
                    maxOf(
                        maximumHitboxHalfWidth,
                        entityStore.hitbox(slot)
                            .halfWidth
                    )

                maximumHitboxHeight =
                    maxOf(
                        maximumHitboxHeight,
                        entityStore.hitbox(slot)
                            .height
                    )
            }

            slot++
        }
    }

    inline fun forEachNearby(
        position: SimulatedVector3,
        radius: Double,
        action: (slot: Int) -> Unit
    ) {
        require(position.isFinite)
        require(radius.isFinite())
        require(radius >= 0.0)

        if (cells.isEmpty()) return

        val radiusInCells =
            ceil(radius / cellSize)
                .toLong()

        val centerCellX =
            floor(position.x / cellSize).toLong()
                .coerceIn(SimulatedSpatialCell.MINIMUM_X.toLong(), SimulatedSpatialCell.MAXIMUM_X.toLong())

        val centerCellY =
            floor(position.y / cellSize).toLong()
                .coerceIn(SimulatedSpatialCell.MINIMUM_Y.toLong(), SimulatedSpatialCell.MAXIMUM_Y.toLong())

        val centerCellZ =
            floor(position.z / cellSize).toLong()
                .coerceIn(SimulatedSpatialCell.MINIMUM_Z.toLong(), SimulatedSpatialCell.MAXIMUM_Z.toLong())

        val minimumCellX =
            lowerBound(
                centerCellX,
                radiusInCells,
                SimulatedSpatialCell.MINIMUM_X
            )

        val maximumCellX =
            upperBound(
                centerCellX,
                radiusInCells,
                SimulatedSpatialCell.MAXIMUM_X
            )

        val minimumCellY =
            lowerBound(
                centerCellY,
                radiusInCells,
                SimulatedSpatialCell.MINIMUM_Y
            )

        val maximumCellY =
            upperBound(
                centerCellY,
                radiusInCells,
                SimulatedSpatialCell.MAXIMUM_Y
            )

        val minimumCellZ =
            lowerBound(
                centerCellZ,
                radiusInCells,
                SimulatedSpatialCell.MINIMUM_Z
            )

        val maximumCellZ =
            upperBound(
                centerCellZ,
                radiusInCells,
                SimulatedSpatialCell.MAXIMUM_Z
            )

        forEachCell(
            minimumCellX,
            minimumCellY,
            minimumCellZ,
            maximumCellX,
            maximumCellY,
            maximumCellZ,
            action
        )
    }

    inline fun forEachCell(
        minimumCellX: Int,
        minimumCellY: Int,
        minimumCellZ: Int,
        maximumCellX: Int,
        maximumCellY: Int,
        maximumCellZ: Int,
        action: (slot: Int) -> Unit
    ) {
        require(minimumCellX <= maximumCellX)
        require(minimumCellY <= maximumCellY)
        require(minimumCellZ <= maximumCellZ)

        val startX =
            minimumCellX.coerceAtLeast(SimulatedSpatialCell.MINIMUM_X)

        val endX =
            maximumCellX.coerceAtMost(SimulatedSpatialCell.MAXIMUM_X)

        val startY =
            minimumCellY.coerceAtLeast(SimulatedSpatialCell.MINIMUM_Y)

        val endY =
            maximumCellY.coerceAtMost(SimulatedSpatialCell.MAXIMUM_Y)

        val startZ =
            minimumCellZ.coerceAtLeast(SimulatedSpatialCell.MINIMUM_Z)

        val endZ =
            maximumCellZ.coerceAtMost(SimulatedSpatialCell.MAXIMUM_Z)

        if (startX > endX || startY > endY || startZ > endZ) {
            return
        }

        val spanX = endX.toLong() - startX.toLong() + 1L
        val spanY = endY.toLong() - startY.toLong() + 1L
        val spanZ = endZ.toLong() - startZ.toLong() + 1L

        if (exceedsActiveCellCount(spanX, spanY, spanZ)) {
            val iterator = cells.long2ObjectEntrySet().fastIterator()

            while (iterator.hasNext()) {
                val entry = iterator.next()
                val packed = entry.longKey
                val cellX = SimulatedSpatialCell.unpackX(packed)
                val cellY = SimulatedSpatialCell.unpackY(packed)
                val cellZ = SimulatedSpatialCell.unpackZ(packed)

                if (
                    cellX !in startX..endX ||
                    cellY !in startY..endY ||
                    cellZ !in startZ..endZ
                ) {
                    continue
                }

                val entries = entry.value
                var index = 0

                while (index < entries.size) {
                    action(entries.getInt(index))
                    index++
                }
            }

            return
        }

        var cellY = startY

        while (cellY <= endY) {
            var cellZ = startZ

            while (cellZ <= endZ) {
                var cellX = startX

                while (cellX <= endX) {
                    val entries =
                        cells[
                            SimulatedSpatialCell.pack(
                                cellX,
                                cellY,
                                cellZ
                            )
                        ]

                    if (entries != null) {
                        var index = 0

                        while (index < entries.size) {
                            action(
                                entries.getInt(index)
                            )

                            index++
                        }
                    }

                    cellX++
                }

                cellZ++
            }

            cellY++
        }
    }

    fun cellOf(
        position: SimulatedVector3
    ): SimulatedSpatialCell =
        SimulatedSpatialCell.from(
            position,
            cellSize
        )

    private fun lowerBound(
        center: Long,
        radius: Long,
        minimum: Int
    ): Int {
        val minimumLong = minimum.toLong()
        return if (radius >= center - minimumLong) minimum else (center - radius).toInt()
    }

    private fun upperBound(
        center: Long,
        radius: Long,
        maximum: Int
    ): Int {
        val maximumLong = maximum.toLong()
        return if (radius >= maximumLong - center) maximum else (center + radius).toInt()
    }

    private fun exceedsActiveCellCount(
        spanX: Long,
        spanY: Long,
        spanZ: Long
    ): Boolean {
        val activeCellCount = cells.size.toLong()
        if (activeCellCount == 0L || spanX > activeCellCount) return true

        val spanXY = spanX * spanY
        if (spanXY > activeCellCount) return true

        return spanZ > activeCellCount / spanXY
    }

    private fun add(
        slot: Int,
        position: SimulatedVector3
    ) {
        check(SimulatedSpatialCell.isValidPosition(position, cellSize)) {
            "Position $position exceeds spatial bounds for cell size $cellSize"
        }

        val cell =
            SimulatedSpatialCell.from(
                position,
                cellSize
            )

        val key =
            SimulatedSpatialCell.pack(
                cell
            )

        val entries =
            cells.get(key)
                ?: IntArrayList().also {
                    cells.put(
                        key,
                        it
                    )
                }

        entries.add(slot)
        indexedEntityCount++
    }
}
