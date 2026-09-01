package zaqws.zycos.simulated

import zaqws.zycos.simulated.map.BakedChunk
import zaqws.zycos.simulated.map.CollisionColumn
import zaqws.zycos.simulated.map.CollisionKind
import zaqws.zycos.simulated.map.SimulatedBounds
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.SimulatedMapConfig
import zaqws.zycos.simulated.map.WalkSurface
import zaqws.zycos.simulated.map.WalkSurfaceChunk

internal object TestSimulatedMapFactory {
    data class Cell(
        val floorHeightUnits: Int =
            SimulatedMapConfig.UNITS_PER_BLOCK,
        val ceilingHeightUnits: Int =
            6 * SimulatedMapConfig.UNITS_PER_BLOCK,
        val waterDepthUnits: Int = 0,
        val walkable: Boolean = true
    )

    fun create(
        chunkCountX: Int = 1,
        chunkCountZ: Int = 1,
        maximumY: Int = 5,
        cellProvider: (worldX: Int, worldZ: Int) -> Cell =
            { _, _ -> Cell() }
    ): SimulatedMap {
        require(chunkCountX > 0)
        require(chunkCountZ > 0)
        require(maximumY >= 1)

        val collisionChunks =
            ArrayList<BakedChunk>()

        val walkSurfaceChunks =
            ArrayList<WalkSurfaceChunk>()

        var chunkZ = 0

        while (chunkZ < chunkCountZ) {
            var chunkX = 0

            while (chunkX < chunkCountX) {
                val collisionColumns =
                    Array(BakedChunk.COLUMN_COUNT) {
                        CollisionColumn.EMPTY
                    }

                val walkSurfaceColumns =
                    Array(WalkSurfaceChunk.COLUMN_COUNT) {
                        emptyList<WalkSurface>()
                    }

                var localZ = 0

                while (localZ < BakedChunk.CHUNK_SIZE) {
                    var localX = 0

                    while (localX < BakedChunk.CHUNK_SIZE) {
                        val worldX =
                            chunkX * BakedChunk.CHUNK_SIZE +
                                    localX

                        val worldZ =
                            chunkZ * BakedChunk.CHUNK_SIZE +
                                    localZ

                        val cell =
                            cellProvider(
                                worldX,
                                worldZ
                            )

                        val columnIndex =
                            localZ * BakedChunk.CHUNK_SIZE +
                                    localX

                        val collisionKinds =
                            collisionKinds(
                                cell,
                                maximumY
                            )

                        collisionColumns[columnIndex] =
                            CollisionColumn.fromBlocks(
                                minimumY = 0,
                                kinds = collisionKinds
                            )

                        if (cell.walkable) {
                            walkSurfaceColumns[columnIndex] =
                                listOf(
                                    WalkSurface(
                                        floorHeightUnits =
                                            cell.floorHeightUnits,
                                        ceilingHeightUnits =
                                            cell.ceilingHeightUnits,
                                        supportKind =
                                            supportKind(cell),
                                        waterDepthUnits =
                                            cell.waterDepthUnits
                                    )
                                )
                        }

                        localX++
                    }

                    localZ++
                }

                collisionChunks.add(
                    BakedChunk(
                        chunkX = chunkX,
                        chunkZ = chunkZ,
                        minimumY = 0,
                        maximumY = maximumY,
                        columns = collisionColumns
                    )
                )

                walkSurfaceChunks.add(
                    WalkSurfaceChunk(
                        chunkX = chunkX,
                        chunkZ = chunkZ,
                        columns = walkSurfaceColumns
                    )
                )

                chunkX++
            }

            chunkZ++
        }

        return SimulatedMap.create(
            bounds =
                SimulatedBounds(
                    minimumX = 0,
                    minimumY = 0,
                    minimumZ = 0,
                    maximumX =
                        chunkCountX *
                                BakedChunk.CHUNK_SIZE - 1,
                    maximumY = maximumY,
                    maximumZ =
                        chunkCountZ *
                                BakedChunk.CHUNK_SIZE - 1
                ),
            config = SimulatedMapConfig.DEFAULT,
            collisionChunks = collisionChunks,
            walkSurfaceChunks = walkSurfaceChunks
        )
    }

    fun createBridgeOverDeepWater(): SimulatedMap {
        val collisionColumns =
            Array(BakedChunk.COLUMN_COUNT) {
                CollisionColumn.EMPTY
            }

        val walkSurfaceColumns =
            Array(WalkSurfaceChunk.COLUMN_COUNT) {
                emptyList<WalkSurface>()
            }

        var localZ = 0

        while (localZ < BakedChunk.CHUNK_SIZE) {
            var localX = 0

            while (localX < BakedChunk.CHUNK_SIZE) {
                val columnIndex =
                    localZ * BakedChunk.CHUNK_SIZE +
                            localX

                if (localZ == 0) {
                    collisionColumns[columnIndex] =
                        CollisionColumn.fromBlocks(
                            minimumY = 0,
                            kinds =
                                arrayOf(
                                    CollisionKind.FULL,
                                    CollisionKind.AIR,
                                    CollisionKind.FULL,
                                    CollisionKind.AIR,
                                    CollisionKind.AIR,
                                    CollisionKind.AIR
                                )
                        )

                    walkSurfaceColumns[columnIndex] =
                        listOf(
                            WalkSurface(
                                floorHeightUnits = 16,
                                ceilingHeightUnits = 32,
                                supportKind =
                                    CollisionKind.FULL,
                                waterDepthUnits = 32
                            ),
                            WalkSurface(
                                floorHeightUnits = 48,
                                ceilingHeightUnits = 96,
                                supportKind =
                                    CollisionKind.FULL
                            )
                        )
                } else {
                    collisionColumns[columnIndex] =
                        CollisionColumn.fromBlocks(
                            minimumY = 0,
                            kinds =
                                Array(6) {
                                    CollisionKind.FULL
                                }
                        )
                }

                localX++
            }

            localZ++
        }

        return SimulatedMap.create(
            bounds =
                SimulatedBounds(
                    minimumX = 0,
                    minimumY = 0,
                    minimumZ = 0,
                    maximumX = 15,
                    maximumY = 5,
                    maximumZ = 15
                ),
            config = SimulatedMapConfig.DEFAULT,
            collisionChunks =
                listOf(
                    BakedChunk(
                        chunkX = 0,
                        chunkZ = 0,
                        minimumY = 0,
                        maximumY = 5,
                        columns = collisionColumns
                    )
                ),
            walkSurfaceChunks =
                listOf(
                    WalkSurfaceChunk(
                        chunkX = 0,
                        chunkZ = 0,
                        columns = walkSurfaceColumns
                    )
                )
        )
    }

    private fun collisionKinds(
        cell: Cell,
        maximumY: Int
    ): Array<CollisionKind> {
        val kinds =
            Array(maximumY + 1) {
                CollisionKind.AIR
            }

        if (!cell.walkable) {
            kinds.fill(
                CollisionKind.FULL
            )

            return kinds
        }

        val fullBlocks =
            cell.floorHeightUnits /
                    SimulatedMapConfig.UNITS_PER_BLOCK

        var y = 0

        while (
            y < fullBlocks &&
            y < kinds.size
        ) {
            kinds[y] = CollisionKind.FULL
            y++
        }

        val remainingHeight =
            cell.floorHeightUnits %
                    SimulatedMapConfig.UNITS_PER_BLOCK

        if (
            remainingHeight > 0 &&
            fullBlocks < kinds.size
        ) {
            require(
                remainingHeight ==
                        SimulatedMapConfig.UNITS_PER_BLOCK / 2
            )

            kinds[fullBlocks] =
                CollisionKind.BOTTOM_SLAB
        }

        return kinds
    }

    private fun supportKind(
        cell: Cell
    ): CollisionKind =
        if (
            cell.floorHeightUnits %
            SimulatedMapConfig.UNITS_PER_BLOCK == 0
        ) {
            CollisionKind.FULL
        } else {
            CollisionKind.BOTTOM_SLAB
        }
}
