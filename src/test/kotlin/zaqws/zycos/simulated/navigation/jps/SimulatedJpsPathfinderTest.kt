package zaqws.zycos.simulated.navigation.jps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.SimulatedMapConfig
import zaqws.zycos.simulated.navigation.NavigationNode
import zaqws.zycos.simulated.navigation.SimulatedAStarPathfinder
import zaqws.zycos.simulated.navigation.SimulatedLocalPathfinder
import zaqws.zycos.simulated.navigation.SimulatedPathRequest
import zaqws.zycos.simulated.navigation.SimulatedPathResult
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile

class SimulatedJpsPathfinderTest {
    private val profile =
        SimulatedTraversalProfile.from(
            width = 1.0,
            height = 2.0
        )

    @Test
    fun `uniform floor path agrees with A star cost`() {
        val map =
            TestSimulatedMapFactory.create()

        val request =
            request(
                map = map,
                start = node(1, 1),
                target = node(12, 9)
            )

        val jpsResult =
            assertInstanceOf(
                SimulatedPathResult.Success::class.java,
                SimulatedJpsPathfinder()
                    .findPath(map, request)
            )

        val aStarResult =
            assertInstanceOf(
                SimulatedPathResult.Success::class.java,
                SimulatedAStarPathfinder()
                    .findPath(map, request)
            )

        assertEquals(
            aStarResult.totalCost,
            jpsResult.totalCost,
            1.0e-7
        )
    }

    @Test
    fun `different floor heights always use local A star fallback`() {
        val map =
            TestSimulatedMapFactory.create { worldX, _ ->
                TestSimulatedMapFactory.Cell(
                    floorHeightUnits =
                        if (worldX == 1) {
                            2 * SimulatedMapConfig.UNITS_PER_BLOCK
                        } else {
                            SimulatedMapConfig.UNITS_PER_BLOCK
                        }
                )
            }

        val fallback =
            RecordingFallback()

        val result =
            SimulatedJpsPathfinder(
                fallbackPathfinder = fallback
            ).findPath(
                map,
                request(
                    map = map,
                    start = node(0, 0),
                    target = node(1, 0, 2)
                )
            )

        assertInstanceOf(
            SimulatedPathResult.Unreachable::class.java,
            result
        )
        assertEquals(1, fallback.invocationCount)
    }

    private fun request(
        map: SimulatedMap,
        start: NavigationNode,
        target: NavigationNode
    ) = SimulatedPathRequest(
        requestId = 1L,
        entityId = SimulatedEntityId(1),
        start = start,
        target = target,
        traversalProfile = profile,
        maximumDropHeightUnits =
            8 * SimulatedMapConfig.UNITS_PER_BLOCK,
        mapRevision = map.revision
    )

    private fun node(
        x: Int,
        z: Int,
        floorBlockY: Int = 1
    ) = NavigationNode(
        x = x,
        z = z,
        floorHeightUnits =
            floorBlockY *
                    SimulatedMapConfig.UNITS_PER_BLOCK
    )

    private class RecordingFallback :
        SimulatedLocalPathfinder {
        var invocationCount = 0
            private set

        override fun findPath(
            map: SimulatedMap,
            request: SimulatedPathRequest
        ): SimulatedPathResult {
            invocationCount++

            return SimulatedPathResult.Unreachable(
                requestId = request.requestId,
                entityId = request.entityId,
                mapRevision = request.mapRevision
            )
        }
    }
}
