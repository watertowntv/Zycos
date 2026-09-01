@file:Suppress("unused")

package zaqws.zycos.simulated.map

import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedBlockPosition
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.paper.map.SimulatedMapRevision
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SimulatedMap internal constructor(
    val bounds: SimulatedBounds,
    val config: SimulatedMapConfig,
    chunks: Iterable<ChunkData>
) {
    companion object {
        internal fun create(
            bounds: SimulatedBounds,
            config: SimulatedMapConfig,
            collisionChunks: Iterable<BakedChunk>,
            walkSurfaceChunks: Iterable<WalkSurfaceChunk>
        ): SimulatedMap {
            val collisionChunksByKey =
                HashMap<Long, BakedChunk>()

            for (chunk in collisionChunks) {
                val key =
                    packChunkCoordinates(
                        chunk.chunkX,
                        chunk.chunkZ
                    )

                require(
                    collisionChunksByKey.put(
                        key,
                        chunk
                    ) == null
                ) {
                    "Duplicate collision chunk: " +
                            "${chunk.chunkX}, ${chunk.chunkZ}"
                }
            }

            val walkSurfaceChunksByKey =
                HashMap<Long, WalkSurfaceChunk>()

            for (chunk in walkSurfaceChunks) {
                val key =
                    packChunkCoordinates(
                        chunk.chunkX,
                        chunk.chunkZ
                    )

                require(
                    walkSurfaceChunksByKey.put(
                        key,
                        chunk
                    ) == null
                ) {
                    "Duplicate walk surface chunk: " +
                            "${chunk.chunkX}, ${chunk.chunkZ}"
                }
            }

            require(
                collisionChunksByKey.keys ==
                        walkSurfaceChunksByKey.keys
            ) {
                "Collision chunks and walk surface chunks " +
                        "must contain identical chunk coordinates"
            }

            val chunkData =
                ArrayList<ChunkData>(
                    collisionChunksByKey.size
                )

            for (
            (key, collisionChunk)
            in collisionChunksByKey
            ) {
                val walkSurfaceChunk =
                    checkNotNull(
                        walkSurfaceChunksByKey[key]
                    )

                chunkData.add(
                    ChunkData(
                        collisionChunk =
                            collisionChunk,

                        walkSurfaceChunk =
                            walkSurfaceChunk,

                        revision =
                            SimulatedMapRevision.INITIAL
                    )
                )
            }

            return SimulatedMap(
                bounds,
                config,
                chunkData
            )
        }

        internal fun packChunkCoordinates(
            chunkX: Int,
            chunkZ: Int
        ): Long =
            (chunkX.toLong() shl Int.SIZE_BITS) or
                    (chunkZ.toLong() and 0xFFFF_FFFFL)

        internal fun unpackChunkX(
            packed: Long
        ): Int =
            (packed shr Int.SIZE_BITS).toInt()

        internal fun unpackChunkZ(
            packed: Long
        ): Int =
            packed.toInt()
    }

    internal data class ChunkData(
        val collisionChunk: BakedChunk,
        val walkSurfaceChunk: WalkSurfaceChunk,
        val revision: SimulatedMapRevision
    ) {
        init {
            require(
                collisionChunk.chunkX ==
                        walkSurfaceChunk.chunkX
            )

            require(
                collisionChunk.chunkZ ==
                        walkSurfaceChunk.chunkZ
            )
        }

        val chunkX: Int
            get() = collisionChunk.chunkX

        val chunkZ: Int
            get() = collisionChunk.chunkZ
    }

    private val chunks =
        ConcurrentHashMap<Long, ChunkData>()

    private val revisionValue =
        AtomicLong(
            SimulatedMapRevision.INITIAL.value
        )

    val revision: SimulatedMapRevision
        get() =
            SimulatedMapRevision(
                revisionValue.get()
            )

    val chunkCount: Int
        get() = chunks.size

    init {
        require(
            config.chunkSize ==
                    SimulatedBlockPosition.CHUNK_SIZE
        ) {
            "SimulatedMap currently requires a " +
                    "${SimulatedBlockPosition.CHUNK_SIZE}x" +
                    "${SimulatedBlockPosition.CHUNK_SIZE} chunk size"
        }

        for (chunk in chunks) {
            require(
                bounds.containsChunk(
                    chunk.chunkX,
                    chunk.chunkZ
                )
            )

            require(
                chunk.collisionChunk.minimumY <=
                        bounds.minimumY
            )

            require(
                chunk.collisionChunk.maximumY >=
                        bounds.maximumY
            )

            val key = packChunkCoordinates(
                chunk.chunkX,
                chunk.chunkZ
            )

            require(this.chunks.putIfAbsent(key, chunk) == null) {
                "Duplicate simulated map chunk: " +
                        "${chunk.chunkX}, ${chunk.chunkZ}"
            }
        }
    }

    fun collisionKindAt(
        x: Int,
        y: Int,
        z: Int
    ): CollisionKind {
        if (!bounds.contains(x, y, z)) {
            return CollisionKind.FULL
        }

        val chunk =
            collisionChunk(
                x shr SimulatedBlockPosition.CHUNK_SHIFT,
                z shr SimulatedBlockPosition.CHUNK_SHIFT
            ) ?: return CollisionKind.FULL

        return chunk.collisionKindAtLocal(
            x and SimulatedBlockPosition.CHUNK_MASK,
            y,
            z and SimulatedBlockPosition.CHUNK_MASK
        )
    }

    fun collisionKindAt(
        position: SimulatedBlockPosition
    ): CollisionKind =
        collisionKindAt(
            position.x,
            position.y,
            position.z
        )

    fun collisionChunk(
        chunkX: Int,
        chunkZ: Int
    ): BakedChunk? =
        chunkData(
            chunkX,
            chunkZ
        )?.collisionChunk

    fun walkSurfaceChunk(
        chunkX: Int,
        chunkZ: Int
    ): WalkSurfaceChunk? =
        chunkData(
            chunkX,
            chunkZ
        )?.walkSurfaceChunk

    fun chunkRevision(
        chunkX: Int,
        chunkZ: Int
    ): SimulatedMapRevision? =
        chunkData(
            chunkX,
            chunkZ
        )?.revision

    fun surfaceCountAt(
        worldX: Int,
        worldZ: Int
    ): Int {
        if (
            worldX !in bounds.minimumX..bounds.maximumX ||
            worldZ !in bounds.minimumZ..bounds.maximumZ
        ) {
            return 0
        }

        val chunk =
            walkSurfaceChunk(
                worldX shr SimulatedBlockPosition.CHUNK_SHIFT,
                worldZ shr SimulatedBlockPosition.CHUNK_SHIFT
            ) ?: return 0

        return chunk.surfaceCountAt(
            worldX and SimulatedBlockPosition.CHUNK_MASK,
            worldZ and SimulatedBlockPosition.CHUNK_MASK
        )
    }

    fun surfaceAt(
        worldX: Int,
        worldZ: Int,
        surfaceIndex: Int
    ): WalkSurface? {
        if (
            worldX !in bounds.minimumX..bounds.maximumX ||
            worldZ !in bounds.minimumZ..bounds.maximumZ
        ) {
            return null
        }

        val chunk =
            walkSurfaceChunk(
                worldX shr SimulatedBlockPosition.CHUNK_SHIFT,
                worldZ shr SimulatedBlockPosition.CHUNK_SHIFT
            ) ?: return null

        val localX =
            worldX and SimulatedBlockPosition.CHUNK_MASK

        val localZ =
            worldZ and SimulatedBlockPosition.CHUNK_MASK

        if (
            surfaceIndex !in
            0 until chunk.surfaceCountAt(
                localX,
                localZ
            )
        ) {
            return null
        }

        return chunk.surfaceAt(
            localX,
            localZ,
            surfaceIndex
        )
    }

    fun nearestSurface(
        worldX: Int,
        worldZ: Int,
        heightUnits: Int,
        maximumDifferenceUnits: Int = Int.MAX_VALUE
    ): WalkSurface? {
        if (
            worldX !in bounds.minimumX..bounds.maximumX ||
            worldZ !in bounds.minimumZ..bounds.maximumZ
        ) {
            return null
        }

        val chunk =
            walkSurfaceChunk(
                worldX shr SimulatedBlockPosition.CHUNK_SHIFT,
                worldZ shr SimulatedBlockPosition.CHUNK_SHIFT
            ) ?: return null

        return chunk.nearestSurface(
            worldX and SimulatedBlockPosition.CHUNK_MASK,
            worldZ and SimulatedBlockPosition.CHUNK_MASK,
            heightUnits,
            maximumDifferenceUnits
        )
    }

    fun highestSurfaceAtOrBelow(
        worldX: Int,
        worldZ: Int,
        maximumHeightUnits: Int
    ): WalkSurface? {
        if (
            worldX !in bounds.minimumX..bounds.maximumX ||
            worldZ !in bounds.minimumZ..bounds.maximumZ
        ) {
            return null
        }

        val chunk =
            walkSurfaceChunk(
                worldX shr SimulatedBlockPosition.CHUNK_SHIFT,
                worldZ shr SimulatedBlockPosition.CHUNK_SHIFT
            ) ?: return null

        return chunk.highestSurfaceAtOrBelow(
            worldX and SimulatedBlockPosition.CHUNK_MASK,
            worldZ and SimulatedBlockPosition.CHUNK_MASK,
            maximumHeightUnits
        )
    }

    fun contains(
        position: SimulatedVector3
    ): Boolean =
        position in bounds

    fun contains(
        boundingBox: SimulatedAABB
    ): Boolean =
        boundingBox.minimumX >=
                bounds.minimumX.toDouble() &&
                boundingBox.maximumX <=
                bounds.maximumX + 1.0 &&
                boundingBox.minimumY >=
                bounds.minimumY.toDouble() &&
                boundingBox.maximumY <=
                bounds.maximumY + 1.0 &&
                boundingBox.minimumZ >=
                bounds.minimumZ.toDouble() &&
                boundingBox.maximumZ <=
                bounds.maximumZ + 1.0

    fun hasCollision(
        boundingBox: SimulatedAABB
    ): Boolean {
        if (!contains(boundingBox)) {
            return true
        }

        if (
            boundingBox.width <=
            SimulatedMath.EPSILON ||
            boundingBox.height <=
            SimulatedMath.EPSILON ||
            boundingBox.depth <=
            SimulatedMath.EPSILON
        ) {
            return false
        }

        val minimumBlockX =
            SimulatedMath.floorToInt(
                boundingBox.minimumX +
                        SimulatedMath.EPSILON
            )

        val maximumBlockX =
            SimulatedMath.floorToInt(
                boundingBox.maximumX -
                        SimulatedMath.EPSILON
            )

        val minimumBlockZ =
            SimulatedMath.floorToInt(
                boundingBox.minimumZ +
                        SimulatedMath.EPSILON
            )

        val maximumBlockZ =
            SimulatedMath.floorToInt(
                boundingBox.maximumZ -
                        SimulatedMath.EPSILON
            )

        var worldZ = minimumBlockZ

        while (worldZ <= maximumBlockZ) {
            val chunkZ =
                worldZ shr
                        SimulatedBlockPosition.CHUNK_SHIFT

            val localZ =
                worldZ and
                        SimulatedBlockPosition.CHUNK_MASK

            var worldX = minimumBlockX

            while (worldX <= maximumBlockX) {
                val chunkX =
                    worldX shr
                            SimulatedBlockPosition.CHUNK_SHIFT

                val localX =
                    worldX and
                            SimulatedBlockPosition.CHUNK_MASK

                val chunk =
                    collisionChunk(
                        chunkX,
                        chunkZ
                    ) ?: return true

                if (
                    chunk.hasCollision(
                        localX,
                        localZ,
                        boundingBox.minimumY,
                        boundingBox.maximumY
                    )
                ) {
                    return true
                }

                worldX++
            }

            worldZ++
        }

        return false
    }

    fun applyPatch(
        patch: SimulatedMapPatch
    ): SimulatedMapRevision {
        synchronized(this) {
            for (replacement in patch.replacements) {
                require(
                    bounds.containsChunk(
                        replacement.chunkX,
                        replacement.chunkZ
                    )
                ) {
                    "Chunk outside simulated map bounds: " +
                            "${replacement.chunkX}, " +
                            "${replacement.chunkZ}"
                }

                require(
                    replacement.collisionChunk.minimumY <=
                            bounds.minimumY
                )

                require(
                    replacement.collisionChunk.maximumY >=
                            bounds.maximumY
                )
            }

            val nextRevision =
                nextRevision()

            for (replacement in patch.replacements) {
                val key =
                    packChunkCoordinates(
                        replacement.chunkX,
                        replacement.chunkZ
                    )

                chunks[key] =
                    ChunkData(
                        collisionChunk =
                            replacement.collisionChunk,

                        walkSurfaceChunk =
                            replacement.walkSurfaceChunk,

                        revision =
                            nextRevision
                    )
            }

            revisionValue.set(
                nextRevision.value
            )

            return nextRevision
        }
    }

    internal fun chunkData(
        chunkX: Int,
        chunkZ: Int
    ): ChunkData? =
        chunks[
            packChunkCoordinates(
                chunkX,
                chunkZ
            )
        ]

    internal fun chunkDataSnapshot(): List<ChunkData> =
        chunks.values.toList()

    private fun nextRevision(): SimulatedMapRevision {
        val currentRevision =
            revisionValue.get()

        check(currentRevision < Long.MAX_VALUE) {
            "Simulated map revision space exhausted"
        }

        return SimulatedMapRevision(
            currentRevision + 1L
        )
    }
}