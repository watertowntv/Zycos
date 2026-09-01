package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.map.SimulatedMap

fun interface SimulatedLocalPathfinder {
    fun findPath(
        map: SimulatedMap,
        request: SimulatedPathRequest
    ): SimulatedPathResult
}