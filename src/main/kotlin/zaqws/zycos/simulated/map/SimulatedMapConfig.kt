@file:Suppress("unused")

package zaqws.zycos.simulated.map

data class SimulatedMapConfig(
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    val maximumStepHeightUnits: Int = DEFAULT_MAXIMUM_STEP_HEIGHT_UNITS,
    val minimumWalkableWaterDepthUnits: Int = DEFAULT_MINIMUM_WALKABLE_WATER_DEPTH_UNITS,
    val maximumWalkableWaterDepthUnits: Int = DEFAULT_MAXIMUM_WALKABLE_WATER_DEPTH_UNITS,
    val patchRadiusChunks: Int = DEFAULT_PATCH_RADIUS_CHUNKS
) {
    companion object {
        const val UNITS_PER_BLOCK = 16

        const val DEFAULT_CHUNK_SIZE = 16

        const val DEFAULT_MAXIMUM_STEP_HEIGHT_UNITS =
            UNITS_PER_BLOCK

        const val DEFAULT_MINIMUM_WALKABLE_WATER_DEPTH_UNITS = 0

        const val DEFAULT_MAXIMUM_WALKABLE_WATER_DEPTH_UNITS =
            UNITS_PER_BLOCK

        const val DEFAULT_PATCH_RADIUS_CHUNKS = 1

        val DEFAULT = SimulatedMapConfig()
    }

    init {
        require(chunkSize > 0)
        require(chunkSize and (chunkSize - 1) == 0)

        require(maximumStepHeightUnits >= 0)

        require(minimumWalkableWaterDepthUnits >= 0)
        require(maximumWalkableWaterDepthUnits >= minimumWalkableWaterDepthUnits)

        require(patchRadiusChunks >= 0)
    }

    val maximumStepHeight: Double
        get() =
            maximumStepHeightUnits.toDouble() /
                    UNITS_PER_BLOCK

    val minimumWalkableWaterDepth: Double
        get() =
            minimumWalkableWaterDepthUnits.toDouble() /
                    UNITS_PER_BLOCK

    val maximumWalkableWaterDepth: Double
        get() =
            maximumWalkableWaterDepthUnits.toDouble() /
                    UNITS_PER_BLOCK
}