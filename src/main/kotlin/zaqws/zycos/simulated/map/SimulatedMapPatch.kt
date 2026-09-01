package zaqws.zycos.simulated.map

class SimulatedMapPatch internal constructor(
    replacements: Iterable<ChunkReplacement>
) {
    companion object {
        internal fun of(
            replacements: Iterable<ChunkReplacement>
        ) = SimulatedMapPatch(replacements)

        internal fun replacement(
            collisionChunk: BakedChunk,
            walkSurfaceChunk: WalkSurfaceChunk
        ) = ChunkReplacement(
            collisionChunk,
            walkSurfaceChunk
        )

        private fun packChunkCoordinates(
            chunkX: Int,
            chunkZ: Int
        ): Long =
            (chunkX.toLong() shl Int.SIZE_BITS) or
                    (chunkZ.toLong() and 0xFFFF_FFFFL)
    }

    internal val replacements: List<ChunkReplacement> =
        replacements.toList()

    init {
        require(this.replacements.isNotEmpty())

        val coordinates = HashSet<Long>(
            this.replacements.size
        )

        for (replacement in this.replacements) {
            require(
                replacement.collisionChunk.chunkX ==
                        replacement.walkSurfaceChunk.chunkX
            )

            require(
                replacement.collisionChunk.chunkZ ==
                        replacement.walkSurfaceChunk.chunkZ
            )

            val key = packChunkCoordinates(
                replacement.chunkX,
                replacement.chunkZ
            )

            require(coordinates.add(key)) {
                "Duplicate chunk replacement: " +
                        "${replacement.chunkX}, ${replacement.chunkZ}"
            }
        }
    }

    val size: Int
        get() = replacements.size

    @ConsistentCopyVisibility
    data class ChunkReplacement internal constructor(
        val collisionChunk: BakedChunk,
        val walkSurfaceChunk: WalkSurfaceChunk
    ) {
        val chunkX: Int
            get() = collisionChunk.chunkX

        val chunkZ: Int
            get() = collisionChunk.chunkZ
    }
}