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
            centerCell.x - radiusInCells

        val maximumCellX =
            centerCell.x + radiusInCells

        val minimumCellY =
            centerCell.y - radiusInCells

        val maximumCellY =
            centerCell.y + radiusInCells

        val minimumCellZ =
            centerCell.z - radiusInCells

        val maximumCellZ =
            centerCell.z + radiusInCells

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