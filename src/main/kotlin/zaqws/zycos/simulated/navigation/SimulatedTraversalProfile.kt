package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.map.SimulatedMapConfig
import zaqws.zycos.simulated.math.SimulatedMath

data class SimulatedTraversalProfile(
    val widthUnits: Int,
    val heightUnits: Int,
    val maximumStepHeightUnits: Int =
        SimulatedMapConfig.UNITS_PER_BLOCK
) {
    companion object {
        fun from(
            width: Double,
            height: Double,
            maximumStepHeight: Double = 1.0
        ): SimulatedTraversalProfile {
            require(width.isFinite())
            require(height.isFinite())
            require(maximumStepHeight.isFinite())

            require(width > 0.0)
            require(height > 0.0)
            require(maximumStepHeight >= 0.0)

            return SimulatedTraversalProfile(
                widthUnits =
                    quantizeUp(width),

                heightUnits =
                    quantizeUp(height),

                maximumStepHeightUnits =
                    quantizeUp(
                        maximumStepHeight
                    )
            )
        }

        private fun quantizeUp(
            value: Double
        ): Int =
            SimulatedMath.ceilToInt(
                SimulatedMath.quantizeUp(
                    value,
                    1.0 /
                            SimulatedMapConfig
                                .UNITS_PER_BLOCK
                ) *
                        SimulatedMapConfig
                            .UNITS_PER_BLOCK
            )
    }

    init {
        require(widthUnits > 0)
        require(heightUnits > 0)
        require(maximumStepHeightUnits >= 0)
    }

    val width: Double
        get() =
            widthUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val height: Double
        get() =
            heightUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK

    val maximumStepHeight: Double
        get() =
            maximumStepHeightUnits.toDouble() /
                    SimulatedMapConfig.UNITS_PER_BLOCK
}
