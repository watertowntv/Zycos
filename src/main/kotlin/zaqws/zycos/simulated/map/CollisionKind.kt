@file:Suppress("unused")

package zaqws.zycos.simulated.map

enum class CollisionKind(
    val collisionHeightUnits: Int,
    val blocksMovement: Boolean,
    val supportsStanding: Boolean
) {
    AIR(
        collisionHeightUnits = 0,
        blocksMovement = false,
        supportsStanding = false
    ),

    FULL(
        collisionHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK,
        blocksMovement = true,
        supportsStanding = true
    ),

    HALF(
        collisionHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK / 2,
        blocksMovement = true,
        supportsStanding = true
    ),

    TALL(
        collisionHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK +
                SimulatedMapConfig.UNITS_PER_BLOCK / 2,
        blocksMovement = true,
        supportsStanding = true
    ),

    DEEP_WATER(
        collisionHeightUnits = SimulatedMapConfig.UNITS_PER_BLOCK,
        blocksMovement = true,
        supportsStanding = false
    );

    val collisionHeight: Double
        get() =
            collisionHeightUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val isAir: Boolean
        get() = this == AIR
}