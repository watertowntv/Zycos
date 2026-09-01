package zaqws.zycos.simulated.map

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollisionColumnTest {
    @Test
    fun `stacked bottom slabs retain the air gap in each block`() {
        val column =
            CollisionColumn.fromBlocks(
                minimumY = 0,
                kinds =
                    arrayOf(
                        CollisionKind.BOTTOM_SLAB,
                        CollisionKind.BOTTOM_SLAB
                    )
            )

        assertTrue(column.intersectsSolid(0.0, 0.5))
        assertFalse(column.intersectsSolid(0.5, 1.0))
        assertTrue(column.intersectsSolid(1.0, 1.5))
        assertFalse(column.intersectsSolid(1.5, 2.0))
    }

    @Test
    fun `top slab occupies only the upper half of its block`() {
        val column =
            CollisionColumn.fromBlocks(
                minimumY = 4,
                kinds =
                    arrayOf(CollisionKind.TOP_SLAB)
            )

        assertFalse(column.intersectsSolid(4.0, 4.5))
        assertTrue(column.intersectsSolid(4.5, 5.0))
    }

    @Test
    fun `full block and tall collision retain their exact upper bounds`() {
        val column =
            CollisionColumn.fromBlocks(
                minimumY = 0,
                kinds =
                    arrayOf(
                        CollisionKind.FULL,
                        CollisionKind.TALL
                    )
            )

        assertTrue(column.intersectsSolid(0.0, 1.0))
        assertTrue(column.intersectsSolid(2.25, 2.5))
        assertFalse(column.intersectsSolid(2.5, 3.0))
    }
}
