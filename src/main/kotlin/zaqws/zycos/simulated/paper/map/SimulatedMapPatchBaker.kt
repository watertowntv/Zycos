@file:Suppress("unused")

package zaqws.zycos.simulated.paper.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.bukkit.ChunkSnapshot
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.simulated.map.BakedChunk
import zaqws.zycos.simulated.map.CollisionColumn
import zaqws.zycos.simulated.map.CollisionKind
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.SimulatedMapConfig
import zaqws.zycos.simulated.map.SimulatedMapPatch
import zaqws.zycos.simulated.map.WalkSurface
import zaqws.zycos.simulated.map.WalkSurfaceChunk
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SimulatedMapPatchBaker(
    private val plugin: JavaPlugin,
    private val world: World,
    private val map: SimulatedMap,
    private val collisionResolver: PaperCollisionResolver =
        PaperCollisionResolver.DEFAULT,
    private val chunksPerBatch: Int =
        DEFAULT_CHUNKS_PER_BATCH
) {
    companion object {
        private const val DEFAULT_CHUNKS_PER_BATCH = 8
    }

    init {
        require(chunksPerBatch > 0)
    }

    data class ChunkCoordinate(
        val chunkX: Int,
        val chunkZ: Int
    )

    suspend fun bakeAround(
        chunkX: Int,
        chunkZ: Int,
        radiusChunks: Int =
            map.config.patchRadiusChunks
    ): SimulatedMapPatch? {
        require(radiusChunks >= 0)

        val coordinates =
            ArrayList<ChunkCoordinate>(
                (radiusChunks * 2 + 1) *
                        (radiusChunks * 2 + 1)
            )

        for (
        offsetZ in
        -radiusChunks..radiusChunks
        ) {
            for (
            offsetX in
            -radiusChunks..radiusChunks
            ) {
                val targetChunkX =
                    chunkX + offsetX

                val targetChunkZ =
                    chunkZ + offsetZ

                if (
                    !map.bounds.containsChunk(
                        targetChunkX,
                        targetChunkZ
                    )
                ) {
                    continue
                }

                coordinates.add(
                    ChunkCoordinate(
                        targetChunkX,
                        targetChunkZ
                    )
                )
            }
        }

        return bakeChunks(coordinates)
    }

    suspend fun bakeChunks(
        coordinates: Collection<ChunkCoordinate>
    ): SimulatedMapPatch? {
        if (coordinates.isEmpty()) {
            return null
        }

        val uniqueCoordinates =
            LinkedHashSet<ChunkCoordinate>(
                coordinates.size
            )

        for (coordinate in coordinates) {
            if (
                map.bounds.containsChunk(
                    coordinate.chunkX,
                    coordinate.chunkZ
                )
            ) {
                uniqueCoordinates.add(
                    coordinate
                )
            }
        }

        if (uniqueCoordinates.isEmpty()) {
            return null
        }

        val replacements =
            ArrayList<SimulatedMapPatch.ChunkReplacement>(
                uniqueCoordinates.size
            )

        val batch =
            ArrayList<ChunkCoordinate>(
                chunksPerBatch
            )

        for (coordinate in uniqueCoordinates) {
            batch.add(coordinate)

            if (
                batch.size >=
                chunksPerBatch
            ) {
                bakeBatch(
                    batch,
                    replacements
                )

                batch.clear()
            }
        }

        if (batch.isNotEmpty()) {
            bakeBatch(
                batch,
                replacements
            )
        }

        if (replacements.isEmpty()) {
            return null
        }

        return SimulatedMapPatch.of(
            replacements
        )
    }

    private suspend fun bakeBatch(
        coordinates: List<ChunkCoordinate>,
        replacements: MutableCollection<
                SimulatedMapPatch.ChunkReplacement
                >
    ) {
        val capturedChunks =
            captureSnapshots(
                coordinates
            )

        val bakedChunks =
            withContext(Dispatchers.Default) {
                capturedChunks.map(
                    ::bakeChunk
                )
            }

        for ((collisionChunk, walkSurfaceChunk) in bakedChunks) {
            replacements.add(
                SimulatedMapPatch.replacement(
                    collisionChunk =
                        collisionChunk,

                    walkSurfaceChunk =
                        walkSurfaceChunk
                )
            )
        }
    }

    private suspend fun captureSnapshots(
        coordinates: List<ChunkCoordinate>
    ): List<CapturedChunk> =
        suspendCancellableCoroutine { continuation ->
            val coordinateCopy =
                coordinates.toList()

            val task =
                plugin.server.scheduler.runTask(
                    plugin,
                    Runnable {
                        if (!continuation.isActive) {
                            return@Runnable
                        }

                        try {
                            val capturedChunks =
                                ArrayList<CapturedChunk>(
                                    coordinateCopy.size
                                )

                            for (
                            (chunkX, chunkZ) in
                            coordinateCopy
                            ) {
                                val snapshot =
                                    world.getChunkAt(
                                        chunkX,
                                        chunkZ
                                    ).getChunkSnapshot(
                                        false,
                                        false,
                                        false
                                    )

                                capturedChunks.add(
                                    CapturedChunk(
                                        chunkX =
                                            chunkX,

                                        chunkZ =
                                            chunkZ,

                                        snapshot =
                                            snapshot
                                    )
                                )
                            }

                            continuation.resume(
                                capturedChunks
                            )
                        } catch (
                            throwable: Throwable
                        ) {
                            continuation
                                .resumeWithException(
                                    throwable
                                )
                        }
                    }
                )

            continuation.invokeOnCancellation {
                if (!task.isCancelled) {
                    task.cancel()
                }
            }
        }

    private fun bakeChunk(
        capturedChunk: CapturedChunk
    ): BakedChunkPair {
        val bounds = map.bounds
        val height = bounds.sizeY.toInt()

        val collisionKinds =
            Array(BakedChunk.COLUMN_COUNT) {
                Array(height) {
                    CollisionKind.AIR
                }
            }

        val water =
            Array(BakedChunk.COLUMN_COUNT) {
                BooleanArray(height)
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
                    capturedChunk =
                        capturedChunk,

                    localX =
                        localX,

                    localZ =
                        localZ,

                    collisionKinds =
                        collisionKinds[columnIndex],

                    water =
                        water[columnIndex]
                )

                convertDeepWater(
                    collisionKinds =
                        collisionKinds[columnIndex],

                    water =
                        water[columnIndex]
                )

                localX++
            }

            localZ++
        }

        val collisionColumns =
            Array(BakedChunk.COLUMN_COUNT) {
                    columnIndex ->

                CollisionColumn.fromBlocks(
                    minimumY =
                        bounds.minimumY,

                    kinds =
                        collisionKinds[columnIndex]
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
                    collisionKinds =
                        collisionKinds[columnIndex],

                    water =
                        water[columnIndex]
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
        capturedChunk: CapturedChunk,
        localX: Int,
        localZ: Int,
        collisionKinds: Array<CollisionKind>,
        water: BooleanArray
    ) {
        val bounds = map.bounds

        var y = bounds.minimumY

        while (y <= bounds.maximumY) {
            val index =
                y - bounds.minimumY

            val blockData =
                capturedChunk.snapshot
                    .getBlockData(
                        localX,
                        y,
                        localZ
                    )

            collisionKinds[index] =
                collisionResolver.resolve(
                    blockData
                )

            water[index] =
                PaperCollisionResolver
                    .isWater(blockData)

            y++
        }
    }

    private fun convertDeepWater(
        collisionKinds: Array<CollisionKind>,
        water: BooleanArray
    ) {
        var index = 0

        while (index < water.size) {
            if (!water[index]) {
                index++
                continue
            }

            val startIndex = index

            while (
                index < water.size &&
                water[index]
            ) {
                index++
            }

            val depth =
                index - startIndex

            if (depth < 2) {
                continue
            }

            var waterIndex = startIndex

            while (waterIndex < index) {
                collisionKinds[waterIndex] =
                    CollisionKind.DEEP_WATER

                waterIndex++
            }
        }
    }

    private fun buildWalkSurfaces(
        collisionKinds: Array<CollisionKind>,
        water: BooleanArray
    ): List<WalkSurface> {
        val bounds = map.bounds

        val surfaces =
            ArrayList<WalkSurface>()

        var index = 0

        while (
            index <
            collisionKinds.size
        ) {
            val supportKind =
                collisionKinds[index]

            if (
                !supportKind.supportsStanding
            ) {
                index++
                continue
            }

            val blockY =
                bounds.minimumY +
                        index

            val floorHeightUnits =
                blockY *
                        SimulatedMapConfig
                            .UNITS_PER_BLOCK +
                        supportKind
                            .collisionHeightUnits

            val nextIndex =
                index + 1

            if (
                nextIndex <
                collisionKinds.size &&
                collisionKinds[nextIndex] ==
                CollisionKind.DEEP_WATER
            ) {
                index++
                continue
            }

            val ceilingHeightUnits =
                findCeilingHeightUnits(
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
                    water =
                        water,

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
        collisionKinds: Array<CollisionKind>,
        startIndex: Int
    ): Int {
        val bounds = map.bounds

        var index =
            startIndex.coerceAtLeast(0)

        while (
            index <
            collisionKinds.size
        ) {
            if (
                collisionKinds[index]
                    .blocksMovement
            ) {
                val blockY =
                    bounds.minimumY +
                            index

                return blockY *
                        SimulatedMapConfig
                            .UNITS_PER_BLOCK
            }

            index++
        }

        return (
                bounds.maximumY + 1
                ) * SimulatedMapConfig
            .UNITS_PER_BLOCK
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
                SimulatedMapConfig
                    .UNITS_PER_BLOCK
    }

    private fun columnIndex(
        localX: Int,
        localZ: Int
    ): Int =
        localZ *
                BakedChunk.CHUNK_SIZE +
                localX

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