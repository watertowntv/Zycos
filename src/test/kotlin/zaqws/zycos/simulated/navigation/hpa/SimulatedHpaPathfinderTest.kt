package zaqws.zycos.simulated.navigation.hpa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.map.SimulatedMapConfig
import zaqws.zycos.simulated.navigation.NavigationNode
import zaqws.zycos.simulated.navigation.SimulatedPathRequest
import zaqws.zycos.simulated.navigation.SimulatedPathResult
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile

class SimulatedHpaPathfinderTest {
    private val profile =
        SimulatedTraversalProfile.from(
            width = 1.0,
            height = 2.0,
            maximumStepHeight = 1.0
        )

    @Test
    fun `continuous chunk entrance is represented by one portal`() {
        val map =
            TestSimulatedMapFactory.create(
                chunkCountX = 2
            )

        val graph =
            SimulatedHpaGraph.build(
                map,
                profile
            )

        assertEquals(1, graph!!.portalCount)
    }

    @Test
    fun `cross chunk drop remains directed and respects request drop limit`() {
        val map =
            TestSimulatedMapFactory.create(
                chunkCountX = 2
            ) { worldX, _ ->
                TestSimulatedMapFactory.Cell(
                    floorHeightUnits =
                        if (worldX < 16) {
                            4 * SimulatedMapConfig.UNITS_PER_BLOCK
                        } else {
                            SimulatedMapConfig.UNITS_PER_BLOCK
                        }
                )
            }

        val pathfinder =
            SimulatedHpaPathfinder()

        val downward =
            pathfinder.findPath(
                map,
                request(
                    mapRevision = map.revision,
                    start = node(15, 0, 4),
                    target = node(16, 0, 1),
                    maximumDropHeightUnits =
                        3 * SimulatedMapConfig.UNITS_PER_BLOCK
                )
            )

        assertInstanceOf(
            SimulatedPathResult.Success::class.java,
            downward
        )

        val upward =
            pathfinder.findPath(
                map,
                request(
                    mapRevision = map.revision,
                    start = node(16, 0, 1),
                    target = node(15, 0, 4),
                    maximumDropHeightUnits =
                        3 * SimulatedMapConfig.UNITS_PER_BLOCK
                )
            )

        assertInstanceOf(
            SimulatedPathResult.Unreachable::class.java,
            upward
        )
    }

    private fun request(
        mapRevision:
            zaqws.zycos.simulated.map.SimulatedMapRevision,
        start: NavigationNode,
        target: NavigationNode,
        maximumDropHeightUnits: Int
    ) = SimulatedPathRequest(
        requestId = 1L,
        entityId = SimulatedEntityId(1),
        start = start,
        target = target,
        traversalProfile = profile,
        maximumDropHeightUnits =
            maximumDropHeightUnits,
        mapRevision = mapRevision
    )

    private fun node(
        x: Int,
        z: Int,
        floorBlockY: Int
    ) = NavigationNode(
        x = x,
        z = z,
        floorHeightUnits =
            floorBlockY *
                    SimulatedMapConfig.UNITS_PER_BLOCK
    )
}
