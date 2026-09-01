@file:Suppress("unused")

package zaqws.zycos.simulated.paper.map

import kotlinx.coroutines.withContext
import org.bukkit.ChunkSnapshot
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.AreaManager
import zaqws.zycos.CoroutineManager.asyncDispatcher
import zaqws.zycos.CoroutineManager.mainDispatcher
import zaqws.zycos.simulated.map.*
import zaqws.zycos.simulated.paper.toSimulatedBounds

class SimulatedMapBaker(
    private val plugin: JavaPlugin,
    private val world: World
) {
    companion object {
        private const val DEFAULT_CHUNKS_PER_BATCH = 8
    }

    private var area: AreaManager.Area? = null
    private var config = SimulatedMapConfig.DEFAULT
    private var collisionResolver = PaperCollisionResolver.DEFAULT
    private var chunksPerBatch = DEFAULT_CHUNKS_PER_BATCH

    fun area(
        area: AreaManager.Area
    ): SimulatedMapBaker {
        this.area = area

        return this
    }

    fun config(
        config: SimulatedMapConfig
    ): SimulatedMapBaker {
        this.config = config

        return this
    }

    fun collisionResolver(
        collisionResolver: PaperCollisionResolver
    ): SimulatedMapBaker {
        this.collisionResolver = collisionResolver

        return this
    }

    fun chunksPerBatch(
        chunksPerBatch: Int
    ): SimulatedMapBaker {
        require(chunksPerBatch > 0)

        this.chunksPerBatch = chunksPerBatch

        return this
    }

    suspend fun bake(): SimulatedMap {
        val area = requireNotNull(area) {
            "Simulated map area is not configured"
        }

        val bounds =
            area.toSimulatedBounds()

        val collisionChunks =
            ArrayList<BakedChunk>()

        val walkSurfaceChunks =
            ArrayList<WalkSurfaceChunk>()

        val pendingCoordinates =
            ArrayList<ChunkCoordinate>(
                chunksPerBatch
            )

        var chunkZ = bounds.minimumChunkZ

        while (chunkZ <= bounds.maximumChunkZ) {
            var chunkX = bounds.minimumChunkX

            while (chunkX <= bounds.maximumChunkX) {
                pendingCoordinates.add(
                    ChunkCoordinate(
                        chunkX,
                        chunkZ
                    )
                )

                if (
                    pendingCoordinates.size >=
                    chunksPerBatch
                ) {
                    bakeBatch(
                        bounds,
                        pendingCoordinates,
                        collisionChunks,
                        walkSurfaceChunks
                    )

                    pendingCoordinates.clear()
                }

                chunkX++
            }

            chunkZ++
        }

        if (pendingCoordinates.isNotEmpty()) {
            bakeBatch(
                bounds,
                pendingCoordinates,
                collisionChunks,
                walkSurfaceChunks
            )
        }

        return SimulatedMap.create(
            bounds = bounds,
            config = config,
            collisionChunks = collisionChunks,
            walkSurfaceChunks = walkSurfaceChunks
        )
    }

    private suspend fun bakeBatch(
        bounds: SimulatedBounds,
        coordinates: List<ChunkCoordinate>,
        collisionChunks: MutableCollection<BakedChunk>,
        walkSurfaceChunks: MutableCollection<WalkSurfaceChunk>
    ) {
        val snapshots =
            captureSnapshots(
                coordinates
            )

        val bakedChunks =
            withContext(plugin.asyncDispatcher) {
                snapshots.map { snapshot ->
                    bakeChunk(
                        bounds,
                        snapshot
                    )
                }
            }

        for ((collisionChunk, walkSurfaceChunk) in bakedChunks) {
            collisionChunks.add(
                collisionChunk
            )

            walkSurfaceChunks.add(
                walkSurfaceChunk
            )
        }
    }

    private suspend fun captureSnapshots(
        coordinates: List<ChunkCoordinate>
    ): List<CapturedChunk> =
        withContext(plugin.mainDispatcher) {
            coordinates.map { (chunkX, chunkZ) ->
                CapturedChunk(
                    chunkX = chunkX,
                    chunkZ = chunkZ,
                    snapshot =
                        world.getChunkAt(
                            chunkX,
                            chunkZ
                        ).getChunkSnapshot(
                            false,
                            false,
                            false
                        )
                )
            }
        }

    private fun bakeChunk(
        bounds: SimulatedBounds,
        capturedChunk: CapturedChunk
    ): BakedChunkPair {
        val resolvedColumns =
            Array(BakedChunk.COLUMN_COUNT) {
                Array(
                    bounds.sizeY.toInt()
                ) {
                    CollisionKind.AIR
                }
            }

        val waterColumns =
            Array(BakedChunk.COLUMN_COUNT) {
                BooleanArray(
                    bounds.sizeY.toInt()
                )
            }

        var localZ = 0

        while (
            localZ <
            BakedChunk.CHUNK_SIZE
        ) {
            var localX = 0

            while (
                localX <
                BakedChunk.CHUNK_SIZE
            ) {
                val columnIndex =
                    columnIndex(
                        localX,
                        localZ
                    )

                resolveColumn(
                    bounds = bounds,
                    capturedChunk = capturedChunk,
                    localX = localX,
                    localZ = localZ,
                    collisionKinds =
                        resolvedColumns[columnIndex],
                    water =
                        waterColumns[columnIndex]
                )

                localX++
            }

            localZ++
        }

        val collisionColumns =
            Array(BakedChunk.COLUMN_COUNT) {
                    columnIndex ->
                CollisionColumn.fromBlocks(
                    minimumY = bounds.minimumY,
                    kinds =
                        resolvedColumns[columnIndex]
                )
            }

        val collisionChunk =
            BakedChunk(
                chunkX =
                    capturedChunk.chunkX,

                chunkZ =
                    capturedChunk.chunkZ,

                minimumY =
                    bounds.minimumY,

                maximumY =
                    bounds.maximumY,

                columns =
                    collisionColumns
            )

        val walkSurfaceColumns =
            Array(BakedChunk.COLUMN_COUNT) {
                    columnIndex ->
                buildWalkSurfaces(
                    bounds = bounds,
                    collisionKinds =
                        resolvedColumns[columnIndex],
                    water =
                        waterColumns[columnIndex]
                )
            }

        val walkSurfaceChunk =
            WalkSurfaceChunk(
                chunkX =
                    capturedChunk.chunkX,

                chunkZ =
                    capturedChunk.chunkZ,

                columns =
                    walkSurfaceColumns
            )

        return BakedChunkPair(
            collisionChunk,
            walkSurfaceChunk
        )
    }

    private fun resolveColumn(
        bounds: SimulatedBounds,
        capturedChunk: CapturedChunk,
        localX: Int,
        localZ: Int,
        collisionKinds: Array<CollisionKind>,
        water: BooleanArray
    ) {
        var y = bounds.minimumY

        while (y <= bounds.maximumY) {
            val index =
                y - bounds.minimumY

            val blockData =
                capturedChunk.snapshot.getBlockData(
                    localX,
                    y,
                    localZ
                )

            collisionKinds[index] =
                collisionResolver.resolve(
                    blockData
                )

            water[index] =
                PaperCollisionResolver.isWater(
                    blockData
                )

            y++
        }
    }

    private fun buildWalkSurfaces(
        bounds: SimulatedBounds,
        collisionKinds: Array<CollisionKind>,
        water: BooleanArray
    ): List<WalkSurface> {
        val surfaces =
            ArrayList<WalkSurface>()

        var index = 0

        while (
            index <
            collisionKinds.size
        ) {
            val supportKind =
                collisionKinds[index]

            if (!supportKind.supportsStanding) {
                index++
                continue
            }

            val blockY =
                bounds.minimumY + index

            val floorHeightUnits =
                blockY *
                        SimulatedMapConfig.UNITS_PER_BLOCK +
                        supportKind.maximumHeightUnits

            val nextIndex =
                index + 1

            val ceilingHeightUnits =
                findCeilingHeightUnits(
                    bounds = bounds,
                    collisionKinds =
                        collisionKinds,
                    startIndex =
                        nextIndex
                )

            if (
                ceilingHeightUnits <=
                floorHeightUnits
            ) {
                index++
                continue
            }

            val waterDepthUnits =
                calculateWaterDepthUnits(
                    water = water,
                    startIndex =
                        nextIndex
                )

            surfaces.add(
                WalkSurface(
                    floorHeightUnits =
                        floorHeightUnits,

                    ceilingHeightUnits =
                        ceilingHeightUnits,

                    supportKind =
                        supportKind,

                    waterDepthUnits =
                        waterDepthUnits
                )
            )

            index++
        }

        return surfaces
    }

    private fun findCeilingHeightUnits(
        bounds: SimulatedBounds,
        collisionKinds: Array<CollisionKind>,
        startIndex: Int
    ): Int {
        var index =
            startIndex.coerceAtLeast(0)

        while (
            index <
            collisionKinds.size
        ) {
            val collisionKind =
                collisionKinds[index]

            if (
                collisionKind.blocksMovement
            ) {
                val blockY =
                    bounds.minimumY + index

                return blockY *
                        SimulatedMapConfig.UNITS_PER_BLOCK +
                        collisionKind.minimumHeightUnits
            }

            index++
        }

        return (
                bounds.maximumY + 1
                ) * SimulatedMapConfig.UNITS_PER_BLOCK
    }

    private fun calculateWaterDepthUnits(
        water: BooleanArray,
        startIndex: Int
    ): Int {
        if (
            startIndex !in
            water.indices
        ) {
            return 0
        }

        var index = startIndex
        var depth = 0

        while (
            index < water.size &&
            water[index]
        ) {
            depth++
            index++
        }

        return depth *
                SimulatedMapConfig.UNITS_PER_BLOCK
    }

    private fun columnIndex(
        localX: Int,
        localZ: Int
    ): Int =
        localZ *
                BakedChunk.CHUNK_SIZE +
                localX

    private data class ChunkCoordinate(
        val chunkX: Int,
        val chunkZ: Int
    )

    private data class CapturedChunk(
        val chunkX: Int,
        val chunkZ: Int,
        val snapshot: ChunkSnapshot
    )

    private data class BakedChunkPair(
        val collisionChunk: BakedChunk,
        val walkSurfaceChunk: WalkSurfaceChunk
    )
}
