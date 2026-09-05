package zaqws.zycos.simulated.navigation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.entity.SimulatedAttributes
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.physics.SimulatedPhysicsConfig
import zaqws.zycos.simulated.physics.SimulatedPhysicsSystem
import zaqws.zycos.simulated.system.SimulatedSystemContext

class SimulatedStepTraversalTest {
    @Test
    fun `entity successfully climbs 1-block step during path following`() {
        val map = TestSimulatedMapFactory.create { worldX, _ ->
            if (worldX >= 2) {
                TestSimulatedMapFactory.Cell(floorHeightUnits = 32)
            } else {
                TestSimulatedMapFactory.Cell(floorHeightUnits = 16)
            }
        }

        val store = SimulatedEntityStore()
        val physicsConfig = SimulatedPhysicsConfig(
            jumpVelocity = 0.42,
            gravityPerTick = -0.08,
            airDrag = 0.98,
            groundFriction = 0.6
        )
        val physicsSystem = SimulatedPhysicsSystem(
            entityStore = store,
            map = map,
            config = physicsConfig,
            damageConsumer = { _, _, _ -> }
        )
        val pathFollower = SimulatedPathFollower(
            entityStore = store,
            physicsConfig = physicsConfig,
            eventConsumer = {}
        )

        val entityId = store.create(
            position = SimulatedVector3(1.5, 1.0, 0.5),
            attributes = SimulatedAttributes(movementSpeed = 0.2)
        )
        val slot = store.requireSlot(entityId)
        store.setFlag(slot, SimulatedEntityFlag.ON_GROUND, true)

        val path = SimulatedPath(
            listOf(
                NavigationNode(1, 1, 16),
                NavigationNode(2, 2, 32),
                NavigationNode(3, 2, 32)
            )
        )
        pathFollower.setPath(entityId, path)

        for (tick in 1L..25L) {
            val tickContext = SimulatedSystemContext(tick = tick, deltaSeconds = 0.05)
            pathFollower.update(tickContext)
            physicsSystem.update(tickContext)
        }

        val finalPosition = store.position(slot)
        assertTrue(
            finalPosition.x >= 2.2 && finalPosition.y >= 2.0,
            "Expected entity to traverse onto elevated block (x >= 2.2, y >= 2.0), but position was $finalPosition"
        )
    }

    @Test
    fun `entity successfully climbs consecutive 1-block steps during path following`() {
        val map = TestSimulatedMapFactory.create { worldX, _ ->
            when {
                worldX >= 3 -> TestSimulatedMapFactory.Cell(floorHeightUnits = 48)
                worldX >= 2 -> TestSimulatedMapFactory.Cell(floorHeightUnits = 32)
                else -> TestSimulatedMapFactory.Cell(floorHeightUnits = 16)
            }
        }

        val store = SimulatedEntityStore()
        val physicsConfig = SimulatedPhysicsConfig(
            jumpVelocity = 0.42,
            gravityPerTick = -0.08,
            airDrag = 0.98,
            groundFriction = 0.6
        )
        val physicsSystem = SimulatedPhysicsSystem(
            entityStore = store,
            map = map,
            config = physicsConfig,
            damageConsumer = { _, _, _ -> }
        )
        val pathFollower = SimulatedPathFollower(
            entityStore = store,
            physicsConfig = physicsConfig,
            eventConsumer = {}
        )

        val entityId = store.create(
            position = SimulatedVector3(1.5, 1.0, 0.5),
            attributes = SimulatedAttributes(movementSpeed = 0.2)
        )
        val slot = store.requireSlot(entityId)
        store.setFlag(slot, SimulatedEntityFlag.ON_GROUND, true)

        val path = SimulatedPath(
            listOf(
                NavigationNode(1, 1, 16),
                NavigationNode(2, 2, 32),
                NavigationNode(3, 3, 48),
                NavigationNode(4, 3, 48)
            )
        )
        pathFollower.setPath(entityId, path)

        for (tick in 1L..50L) {
            val tickContext = SimulatedSystemContext(tick = tick, deltaSeconds = 0.05)
            pathFollower.update(tickContext)
            physicsSystem.update(tickContext)
        }

        val finalPosition = store.position(slot)
        assertTrue(
            finalPosition.x >= 3.2 && finalPosition.y >= 3.0,
            "Expected entity to traverse onto second elevated block (x >= 3.2, y >= 3.0), but position was $finalPosition"
        )
    }
}
