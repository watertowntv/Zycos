package zaqws.zycos.simulated.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkSurfaceTest {
    @Test
    fun `low ceiling rejects entity height`() {
        val surface =
            WalkSurface(
                floorHeightUnits = 16,
                ceilingHeightUnits = 40,
                supportKind = CollisionKind.FULL
            )

        assertTrue(surface.canFit(1.5))
        assertFalse(surface.canFit(2.0))
    }

    @Test
    fun `column retains multiple floors at the same coordinates`() {
        val columns =
            Array(WalkSurfaceChunk.COLUMN_COUNT) {
                emptyList<WalkSurface>()
            }

        columns[0] =
            listOf(
                WalkSurface(
                    floorHeightUnits = 16,
                    ceilingHeightUnits = 48,
                    supportKind = CollisionKind.FULL
                ),
                WalkSurface(
                    floorHeightUnits = 64,
                    ceilingHeightUnits = 96,
                    supportKind = CollisionKind.FULL
                )
            )

        val chunk =
            WalkSurfaceChunk(
                chunkX = 0,
                chunkZ = 0,
                columns = columns
            )

        assertEquals(2, chunk.surfaceCountAt(0, 0))
        assertEquals(
            16,
            chunk.nearestSurface(0, 0, 20)
                ?.floorHeightUnits
        )
        assertEquals(
            64,
            chunk.nearestSurface(0, 0, 60)
                ?.floorHeightUnits
        )
    }
}
