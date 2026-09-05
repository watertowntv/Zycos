package zaqws.zycos.simulated.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.entity.SimulatedEntityId

class SimulatedNavigationServiceTest {
    @Test
    fun testCachedPathResultReturnedToRequestingEntity() {
        val map = TestSimulatedMapFactory.create(chunkCountX = 2)
        val service = SimulatedNavigationService(
            map = map,
            workerCount = 1
        )
        try {
            val profile = SimulatedTraversalProfile.from(
                width = 1.0,
                height = 2.0,
                maximumStepHeight = 1.0
            )
            val start = NavigationNode(1, 1, 16)
            val target = NavigationNode(5, 5, 16)

            val entity1 = SimulatedEntityId(1)
            val req1 = service.requestPath(
                entityId = entity1,
                start = start,
                target = target,
                traversalProfile = profile,
                maximumDropHeightUnits = 16
            )

            val results1 = ArrayList<SimulatedPathResult>()
            val deadline1 = System.currentTimeMillis() + 2000
            while (results1.isEmpty() && System.currentTimeMillis() < deadline1) {
                service.drainResults { results1.add(it) }
                if (results1.isEmpty()) Thread.sleep(5)
            }

            assertEquals(1, results1.size)
            val res1 = assertInstanceOf(SimulatedPathResult.Success::class.java, results1[0])
            assertEquals(entity1, res1.entityId)
            assertEquals(req1, res1.requestId)

            val entity2 = SimulatedEntityId(2)
            val req2 = service.requestPath(
                entityId = entity2,
                start = start,
                target = target,
                traversalProfile = profile,
                maximumDropHeightUnits = 16
            )

            val results2 = ArrayList<SimulatedPathResult>()
            val deadline2 = System.currentTimeMillis() + 2000
            while (results2.isEmpty() && System.currentTimeMillis() < deadline2) {
                service.drainResults { results2.add(it) }
                if (results2.isEmpty()) Thread.sleep(5)
            }

            assertEquals(1, results2.size)
            val res2 = assertInstanceOf(SimulatedPathResult.Success::class.java, results2[0])
            assertEquals(entity2, res2.entityId)
            assertEquals(req2, res2.requestId)
        } finally {
            service.close()
        }
    }
}
