@file:Suppress("unused")

package zaqws.zycos.simulated.spatial

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.math.SimulatedVector3
import kotlin.math.ceil

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

        val radiusInCells =
            ceil(radius / cellSize)
                .toInt()

        val centerCell =
            SimulatedSpatialCell.from(
                position,
                cellSize
            )

        val minimumCellX =
            (centerCell.x - radiusInCells)
                .coerceAtLeast(SimulatedSpatialCell.MINIMUM_X)

        val maximumCellX =
            (centerCell.x + radiusInCells)
                .coerceAtMost(SimulatedSpatialCell.MAXIMUM_X)

        val minimumCellY =
            (centerCell.y - radiusInCells)
                .coerceAtLeast(SimulatedSpatialCell.MINIMUM_Y)

        val maximumCellY =
            (centerCell.y + radiusInCells)
                .coerceAtMost(SimulatedSpatialCell.MAXIMUM_Y)

        val minimumCellZ =
            (centerCell.z - radiusInCells)
                .coerceAtLeast(SimulatedSpatialCell.MINIMUM_Z)

        val maximumCellZ =
            (centerCell.z + radiusInCells)
                .coerceAtMost(SimulatedSpatialCell.MAXIMUM_Z)

        if (minimumCellX > maximumCellX || minimumCellY > maximumCellY || minimumCellZ > maximumCellZ) {
            return
        }

        var cellY = minimumCellY

        while (cellY <= maximumCellY) {
            var cellZ = minimumCellZ

            while (cellZ <= maximumCellZ) {
                var cellX = minimumCellX

                while (cellX <= maximumCellX) {
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

    private fun add(
        slot: Int,
        position: SimulatedVector3
    ) {
        if (!SimulatedSpatialCell.isValidPosition(position, cellSize)) return

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
