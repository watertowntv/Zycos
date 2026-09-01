@file:Suppress("unused")

package zaqws.zycos.simulated.map

data class WalkSurface(
    val floorHeightUnits: Int,
    val ceilingHeightUnits: Int,
    val supportKind: CollisionKind,
    val waterDepthUnits: Int = 0
) {
    init {
        require(
            ceilingHeightUnits >
                    floorHeightUnits
        )

        require(
            supportKind.supportsStanding
        )

        require(waterDepthUnits >= 0)
    }

    val floorHeight: Double
        get() =
            floorHeightUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val ceilingHeight: Double
        get() =
            ceilingHeightUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val clearanceUnits: Int
        get() =
            ceilingHeightUnits -
                    floorHeightUnits

    val clearance: Double
        get() =
            clearanceUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val waterDepth: Double
        get() =
            waterDepthUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val isWater: Boolean
        get() = waterDepthUnits > 0

    fun canFit(
        height: Double
    ): Boolean {
        require(height.isFinite())
        require(height > 0.0)

        val requiredHeightUnits =
            kotlin.math.ceil(
                height *
                        SimulatedMapConfig.UNITS_PER_BLOCK
            ).toInt()

        return requiredHeightUnits <=
                clearanceUnits
    }
}