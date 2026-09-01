package zaqws.zycos.simulated.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.SimulatedMapConfig

class SimulatedAStarPathfinderTest {
    private val pathfinder =
        SimulatedAStarPathfinder()

    private val profile =
        SimulatedTraversalProfile.from(
            width = 1.0,
            height = 2.0,
            maximumStepHeight = 1.0
        )

    @Test
    fun `straight path crosses multiple chunks`() {
        val map =
            TestSimulatedMapFactory.create(
                chunkCountX = 3
            )

        val result =
            find(
                map = map,
                start = node(1, 1),
                target = node(40, 1)
            )

        val success =
            assertInstanceOf(
                SimulatedPathResult.Success::class.java,
                result
            )

        assertEquals(
            node(1, 1),
            success.path.start
        )

        assertEquals(
            node(40, 1),
            success.path.target
        )
    }

    @Test
    fun `diagonal cannot cut through a blocked orthogonal neighbor`() {
        val map =
            TestSimulatedMapFactory.create { worldX, worldZ ->
                TestSimulatedMapFactory.Cell(
                    walkable =
                        worldX != 1 || worldZ != 0
                )
            }

        val result =
            find(
                map = map,
                start = node(0, 0),
                target = node(1, 1)
            )

        val success =
            assertInstanceOf(
                SimulatedPathResult.Success::class.java,
                result
            )

        assertEquals(3, success.path.size)
    }

    @Test
    fun `one block step is valid and higher climb is rejected`() {
        val stepMap =
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

        assertInstanceOf(
            SimulatedPathResult.Success::class.java,
            find(
                map = stepMap,
                start = node(0, 0),
                target = node(1, 0, 2)
            )
        )

        val climbMap =
            TestSimulatedMapFactory.create { worldX, _ ->
                TestSimulatedMapFactory.Cell(
                    floorHeightUnits =
                        if (worldX == 1) {
                            3 * SimulatedMapConfig.UNITS_PER_BLOCK
                        } else {
                            SimulatedMapConfig.UNITS_PER_BLOCK
                        }
                )
            }

        assertInstanceOf(
            SimulatedPathResult.Unreachable::class.java,
            find(
                map = climbMap,
                start = node(0, 0),
                target = node(1, 0, 3)
            )
        )
    }

    @Test
    fun `drop limit distinguishes safe and unsafe falls`() {
        val map =
            TestSimulatedMapFactory.create { worldX, _ ->
                TestSimulatedMapFactory.Cell(
                    floorHeightUnits =
                        if (worldX == 0) {
                            4 * SimulatedMapConfig.UNITS_PER_BLOCK
                        } else {
                            SimulatedMapConfig.UNITS_PER_BLOCK
                        }
                )
            }

        assertInstanceOf(
            SimulatedPathResult.Success::class.java,
            find(
                map = map,
                start = node(0, 0, 4),
                target = node(1, 0),
                maximumDropHeightUnits =
                    3 * SimulatedMapConfig.UNITS_PER_BLOCK
            )
        )

        assertInstanceOf(
            SimulatedPathResult.Unreachable::class.java,
            find(
                map = map,
                start = node(0, 0, 4),
                target = node(1, 0),
                maximumDropHeightUnits =
                    2 * SimulatedMapConfig.UNITS_PER_BLOCK
            )
        )
    }

    @Test
    fun `shallow water is traversable and deep water is not`() {
        val shallowMap =
            TestSimulatedMapFactory.create { worldX, _ ->
                TestSimulatedMapFactory.Cell(
                    waterDepthUnits =
                        if (worldX == 1) {
                            SimulatedMapConfig.UNITS_PER_BLOCK
                        } else {
                            0
                        }
                )
            }

        assertInstanceOf(
            SimulatedPathResult.Success::class.java,
            find(
                map = shallowMap,
                start = node(0, 0),
                target = node(2, 0)
            )
        )

        val deepMap =
            TestSimulatedMapFactory.create { worldX, worldZ ->
                TestSimulatedMapFactory.Cell(
                    waterDepthUnits =
                        if (worldX == 1) {
                            2 * SimulatedMapConfig.UNITS_PER_BLOCK
                        } else {
                            0
                        },
                    walkable =
                        worldZ == 0
                )
            }

        assertInstanceOf(
            SimulatedPathResult.Unreachable::class.java,
            find(
                map = deepMap,
                start = node(0, 0),
                target = node(2, 0)
            )
        )
    }

    @Test
    fun `bridge surface remains traversable above deep water`() {
        val map =
            TestSimulatedMapFactory
                .createBridgeOverDeepWater()

        assertInstanceOf(
            SimulatedPathResult.Success::class.java,
            find(
                map = map,
                start = node(0, 0, 3),
                target = node(15, 0, 3)
            )
        )
    }

    private fun find(
        map: SimulatedMap,
        start: NavigationNode,
        target: NavigationNode,
        maximumDropHeightUnits: Int =
            8 * SimulatedMapConfig.UNITS_PER_BLOCK
    ): SimulatedPathResult =
        pathfinder.findPath(
            map,
            SimulatedPathRequest(
                requestId = 1L,
                entityId = SimulatedEntityId(1),
                start = start,
                target = target,
                traversalProfile = profile,
                maximumDropHeightUnits =
                    maximumDropHeightUnits,
                mapRevision = map.revision
            )
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
}
