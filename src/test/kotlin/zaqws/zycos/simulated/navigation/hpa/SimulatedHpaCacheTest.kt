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
}
