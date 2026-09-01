package zaqws.zycos.simulated.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory

class SimulatedMapPatchTest {
    @Test
    fun `multi chunk replacement publishes one coherent revision`() {
        val map =
            TestSimulatedMapFactory.create(
                chunkCountX = 2
            )

        val replacements =
            TestSimulatedMapFactory.create(
                chunkCountX = 2
            ) { _, _ ->
                TestSimulatedMapFactory.Cell(
                    floorHeightUnits =
                        2 * SimulatedMapConfig.UNITS_PER_BLOCK
                )
            }

        val previousRevision =
            map.revision

        val nextRevision =
            map.applyPatch(
                SimulatedMapPatch.of(
                    listOf(
                        SimulatedMapPatch.replacement(
                            collisionChunk =
                                checkNotNull(
                                    replacements
                                        .collisionChunk(0, 0)
                                ),
                            walkSurfaceChunk =
                                checkNotNull(
                                    replacements
                                        .walkSurfaceChunk(0, 0)
                                )
                        ),
                        SimulatedMapPatch.replacement(
                            collisionChunk =
                                checkNotNull(
                                    replacements
                                        .collisionChunk(1, 0)
                                ),
                            walkSurfaceChunk =
                                checkNotNull(
                                    replacements
                                        .walkSurfaceChunk(1, 0)
                                )
                        )
                    )
                )
            )

        assertNotEquals(
            previousRevision,
            nextRevision
        )

        assertEquals(nextRevision, map.revision)
        assertEquals(nextRevision, map.chunkRevision(0, 0))
        assertEquals(nextRevision, map.chunkRevision(1, 0))
        assertEquals(
            2 * SimulatedMapConfig.UNITS_PER_BLOCK,
            map.nearestSurface(1, 1, 0)
                ?.floorHeightUnits
        )
        assertEquals(
            2 * SimulatedMapConfig.UNITS_PER_BLOCK,
            map.nearestSurface(17, 1, 0)
                ?.floorHeightUnits
        )
    }
}
