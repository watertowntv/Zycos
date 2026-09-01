package zaqws.zycos.simulated.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import zaqws.zycos.AreaManager

class SimulatedPaperConversionsTest {
    @Test
    fun `area and position conversions preserve normalized coordinates`() {
        val area =
            AreaManager.Area(
                AreaManager.Position(8, 20, 12),
                AreaManager.Position(-4, -8, -16)
            )

        val bounds =
            area.toSimulatedBounds()

        assertEquals(-4, bounds.minimumX)
        assertEquals(-8, bounds.minimumY)
        assertEquals(-16, bounds.minimumZ)
        assertEquals(8, bounds.maximumX)
        assertEquals(20, bounds.maximumY)
        assertEquals(12, bounds.maximumZ)
        assertEquals(
            area.boundingBoxStart,
            bounds.toArea().boundingBoxStart
        )
        assertEquals(
            area.boundingBoxEnd,
            bounds.toArea().boundingBoxEnd
        )

        val position =
            AreaManager.Position(
                -3,
                4,
                5
            )

        val blockPosition =
            position.toSimulatedBlockPosition()

        assertEquals(position.x, blockPosition.x)
        assertEquals(position.y, blockPosition.y)
        assertEquals(position.z, blockPosition.z)
        assertEquals(-2.5, position.toSimulatedBlockCenter().x)
        assertEquals(4.0, position.toSimulatedBlockCenter().y)
        assertEquals(5.5, position.toSimulatedBlockCenter().z)
    }

    @Test
    fun `legacy map revision alias resolves to core type`() {
        val legacy:
                zaqws.zycos.simulated.paper.map.SimulatedMapRevision =
            zaqws.zycos.simulated.map.SimulatedMapRevision(
                3L
            )

        val core:
                zaqws.zycos.simulated.map.SimulatedMapRevision =
            legacy

        assertEquals(3L, core.value)
    }
}
