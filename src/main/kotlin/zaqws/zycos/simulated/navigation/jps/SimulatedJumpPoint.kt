@file:Suppress("unused")

package zaqws.zycos.simulated.navigation.jps

import zaqws.zycos.simulated.navigation.NavigationNode

data class SimulatedJumpPoint(
    val node: NavigationNode,
    val directionX: Int,
    val directionZ: Int
) {
    init {
        require(
            directionX in -1..1 &&
                    directionZ in -1..1
        )

        require(
            directionX != 0 ||
                    directionZ != 0
        )
    }

    val diagonal: Boolean
        get() =
            directionX != 0 &&
                    directionZ != 0
}