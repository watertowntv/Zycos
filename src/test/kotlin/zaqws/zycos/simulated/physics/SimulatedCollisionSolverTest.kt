package zaqws.zycos.simulated.physics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedVector3

class SimulatedCollisionSolverTest {
    @Test
    fun `entity lands on bottom slab at half block height`() {
        val map =
            TestSimulatedMapFactory.create { _, _ ->
                TestSimulatedMapFactory.Cell(
                    floorHeightUnits = 8
                )
            }

        val result =
            SimulatedCollisionSolver(map).move(
                boundingBox =
                    SimulatedAABB.fromBottomCenter(
                        position =
                            SimulatedVector3(
                                2.5,
                                2.0,
                                2.5
                            ),
                        width = 1.0,
                        height = 2.0
                    ),
                requestedMovement =
                    SimulatedVector3(
                        0.0,
                        -2.0,
                        0.0
                    )
            )

        assertTrue(result.collidedY)
        assertEquals(
            0.5,
            result.boundingBox.minimumY,
            1.0e-7
        )
    }

    @Test
    fun `solid wall clips horizontal movement`() {
        val map =
            TestSimulatedMapFactory.create { worldX, worldZ ->
                TestSimulatedMapFactory.Cell(
                    walkable =
                        worldX != 2 || worldZ != 1
                )
            }

        val result =
            SimulatedCollisionSolver(map).move(
                boundingBox =
                    SimulatedAABB.fromBottomCenter(
                        position =
                            SimulatedVector3(
                                1.5,
                                1.0,
                                1.5
                            ),
                        width = 1.0,
                        height = 2.0
                    ),
                requestedMovement =
                    SimulatedVector3(
                        1.0,
                        0.0,
                        0.0
                    )
            )

        assertTrue(result.collidedX)
        assertEquals(
            0.0,
            result.movement.x,
            1.0e-7
        )
    }
}
