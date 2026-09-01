@file:Suppress("unused")

package zaqws.zycos.simulated.map

enum class CollisionKind(
    val minimumHeightUnits: Int,
    val maximumHeightUnits: Int,
    val blocksMovement: Boolean,
    val supportsStanding: Boolean
) {
    AIR(
        minimumHeightUnits = 0,
        maximumHeightUnits = 0,
        blocksMovement = false,
        supportsStanding = false
    ),

    FULL(
        minimumHeightUnits = 0,
        maximumHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK,
        blocksMovement = true,
        supportsStanding = true
    ),

    BOTTOM_SLAB(
        minimumHeightUnits = 0,
        maximumHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK / 2,
        blocksMovement = true,
        supportsStanding = true
    ),

    TOP_SLAB(
        minimumHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK / 2,
        maximumHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK,
        blocksMovement = true,
        supportsStanding = true
    ),

    TALL(
        minimumHeightUnits = 0,
        maximumHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK +
                SimulatedMapConfig.UNITS_PER_BLOCK / 2,
        blocksMovement = true,
        supportsStanding = true
    );

    val minimumHeight: Double
        get() =
            minimumHeightUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val maximumHeight: Double
        get() =
            maximumHeightUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val isAir: Boolean
        get() = this == AIR
}
