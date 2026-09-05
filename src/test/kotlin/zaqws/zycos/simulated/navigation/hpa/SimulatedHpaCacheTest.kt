package zaqws.zycos.simulated.navigation.hpa

import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile

class SimulatedHpaCacheTest {
    @Test
    fun `cache distinguishes between different maps with same revision`() {
        val cache = SimulatedHpaCache()
        val profile = SimulatedTraversalProfile.from(
            width = 1.0,
            height = 2.0,
            maximumStepHeight = 1.0
        )
        val map1 = TestSimulatedMapFactory.create(chunkCountX = 2)
        val map2 = TestSimulatedMapFactory.create(chunkCountX = 2)

        val graph1 = cache.graph(map1, profile)
        val graph2 = cache.graph(map2, profile)

        assertNotSame(graph1, graph2)
        assertSame(graph1, cache.graph(map1, profile))
        assertSame(graph2, cache.graph(map2, profile))
    }

    @Test
    fun `path cache isolates requests between different maps with same revision`() {
        val pathCache = zaqws.zycos.simulated.navigation.SimulatedPathCache()
        val map1 = TestSimulatedMapFactory.create(chunkCountX = 2)
        val map2 = TestSimulatedMapFactory.create(chunkCountX = 2)
        val profile = SimulatedTraversalProfile.from(1.0, 2.0, 1.0)

        val request = zaqws.zycos.simulated.navigation.SimulatedPathRequest(
            requestId = 1L,
            entityId = zaqws.zycos.simulated.entity.SimulatedEntityId(1),
            start = zaqws.zycos.simulated.navigation.NavigationNode(0, 0, 0),
            target = zaqws.zycos.simulated.navigation.NavigationNode(5, 5, 0),
            traversalProfile = profile,
            maximumDropHeightUnits = 3,
            mapRevision = map1.revision
        )
        val result = zaqws.zycos.simulated.navigation.SimulatedPathResult.Success(
            requestId = 1L,
            entityId = zaqws.zycos.simulated.entity.SimulatedEntityId(1),
            mapRevision = map1.revision,
            path = zaqws.zycos.simulated.navigation.SimulatedPath(listOf(zaqws.zycos.simulated.navigation.NavigationNode(0, 0, 0))),
            totalCost = 5.0
        )

        pathCache.put(map1, request, result)
        org.junit.jupiter.api.Assertions.assertNotNull(pathCache.get(map1, request))
        org.junit.jupiter.api.Assertions.assertNull(pathCache.get(map2, request))
    }
}
