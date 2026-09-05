package zaqws.zycos.simulated.spatial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.entity.SimulatedHitbox
import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedVector3

class SimulatedEntityQueryTest {
    @Test
    fun `withinAabb finds entity in adjacent cell whose hitbox overlaps query box`() {
        val store = SimulatedEntityStore()
        val spatialIndex = SimulatedSpatialIndex(cellSize = 16.0)
        val query = SimulatedEntityQuery(store, spatialIndex)

        val entityId = store.create(
            position = SimulatedVector3(16.5, 0.0, 0.0),
            hitbox = SimulatedHitbox(width = 2.0, height = 2.0)
        )
        spatialIndex.rebuild(store)

        val queryBox = SimulatedAABB(
            minimumX = 14.0,
            minimumY = 0.0,
            minimumZ = -1.0,
            maximumX = 15.8,
            maximumY = 2.0,
            maximumZ = 1.0
        )

        val results = query.withinAabb(queryBox)
        assertEquals(listOf(entityId), results)
    }

    @Test
    fun `spatial cell packs coordinates covering +-1 million range without overflow`() {
        val cell = SimulatedSpatialCell(
            x = 2_000_000,
            y = 120,
            z = -2_000_000
        )
        val packed = SimulatedSpatialCell.pack(cell)
        org.junit.jupiter.api.Assertions.assertNotEquals(0L, packed)

        org.junit.jupiter.api.Assertions.assertTrue(
            SimulatedSpatialCell.isValidPosition(SimulatedVector3(1_000_000.0, 100.0, -1_000_000.0), 0.5)
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            SimulatedSpatialCell.isValidPosition(SimulatedVector3(10_000_000.0, 100.0, 0.0), 0.5)
        )
    }

    @Test
    fun `spatial index safely handles out of bounds queries`() {
        val spatialIndex = SimulatedSpatialIndex(cellSize = 4.0)
        var visited = 0
        spatialIndex.forEachNearby(SimulatedVector3(100_000_000.0, 0.0, 100_000_000.0), 100.0) {
            visited++
        }
        assertEquals(0, visited)

        spatialIndex.forEachCell(
            minimumCellX = SimulatedSpatialCell.MINIMUM_X - 100,
            minimumCellY = SimulatedSpatialCell.MINIMUM_Y - 100,
            minimumCellZ = SimulatedSpatialCell.MINIMUM_Z - 100,
            maximumCellX = SimulatedSpatialCell.MINIMUM_X - 50,
            maximumCellY = SimulatedSpatialCell.MINIMUM_Y - 50,
            maximumCellZ = SimulatedSpatialCell.MINIMUM_Z - 50
        ) {
            visited++
        }
        assertEquals(0, visited)
    }
}
