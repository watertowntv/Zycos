package zaqws.zycos.simulated.physics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.TestSimulatedMapFactory
import zaqws.zycos.simulated.entity.SimulatedAttributes
import zaqws.zycos.simulated.entity.SimulatedEntityFlag
import zaqws.zycos.simulated.entity.SimulatedEntityFlags
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedEntityStore
import zaqws.zycos.simulated.math.SimulatedVector3
import zaqws.zycos.simulated.navigation.NavigationNode
import zaqws.zycos.simulated.navigation.SimulatedPath
import zaqws.zycos.simulated.navigation.SimulatedPathFollower
import zaqws.zycos.simulated.system.SimulatedSystemContext

class SimulatedPhysicsSystemTest {
    @Test
    fun testJumpReachesOneBlockHeight() {
        val map = TestSimulatedMapFactory.create(chunkCountX = 2)
        val entityStore = SimulatedEntityStore()
        val physicsSystem = SimulatedPhysicsSystem(
            entityStore = entityStore,
            map = map,
            damageConsumer = { _, _, _ -> }
        )

        val entityId = entityStore.create(
            position = SimulatedVector3(2.5, 1.0, 2.5),
            velocity = SimulatedVector3(0.0, 0.42, 0.0),
            flags = SimulatedEntityFlags.of(SimulatedEntityFlag.ON_GROUND)
        )
        val slot = entityStore.slotOf(entityId)

        var maxY = 1.0
        for (i in 1..8) {
            physicsSystem.update(SimulatedSystemContext(i.toLong(), 0.05))
            val currentY = entityStore.position(slot).y
            if (currentY > maxY) {
                maxY = currentY
            }
        }

        assertTrue(maxY >= 2.0, "Entity jump peak $maxY must reach or exceed 2.0 (1 block rise)")
    }

    @Test
    fun testFallDamageIncludesLandingDisplacement() {
        val map = TestSimulatedMapFactory.create(chunkCountX = 2)
        val entityStore = SimulatedEntityStore()
        var receivedDamage = 0.0
        val physicsSystem = SimulatedPhysicsSystem(
            entityStore = entityStore,
            map = map,
            damageConsumer = { _, damage, _ -> receivedDamage = damage }
        )

        val entityId = entityStore.create(
            position = SimulatedVector3(2.5, 4.1, 2.5),
            flags = SimulatedEntityFlags.NONE
        )
        val slot = entityStore.slotOf(entityId)

        for (i in 1..20) {
            physicsSystem.update(SimulatedSystemContext(i.toLong(), 0.05))
            if (entityStore.hasFlag(slot, SimulatedEntityFlag.ON_GROUND)) break
        }

        assertTrue(receivedDamage > 0.0, "Fall from 3.1 blocks must trigger damage, but was $receivedDamage")
        assertEquals(1.0, receivedDamage, 0.01)
    }

    @Test
    fun testKnockbackImpulseRetainedAcrossPathFollowerAndStopping() {
        val map = TestSimulatedMapFactory.create()
        val store = SimulatedEntityStore()
        val physicsConfig = SimulatedPhysicsConfig.DEFAULT
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
            position = SimulatedVector3(2.5, 1.0, 2.5),
            attributes = SimulatedAttributes(movementSpeed = 0.2)
        )
        val slot = store.requireSlot(entityId)
        store.setFlag(slot, SimulatedEntityFlag.ON_GROUND, true)

        val path = SimulatedPath(
            listOf(
                NavigationNode(2, 2, 16),
                NavigationNode(3, 2, 16)
            )
        )
        pathFollower.setPath(entityId, path)

        store.addVelocity(slot, SimulatedVector3(0.0, 0.0, 1.0))

        val tickContext = SimulatedSystemContext(tick = 1L, deltaSeconds = 0.05)
        pathFollower.update(tickContext)

        val impulseBeforePhysics = store.impulseVelocity(slot)
        assertEquals(1.0, impulseBeforePhysics.z, 0.001)

        val steeringBeforePhysics = store.steeringVelocity(slot)
        assertTrue(steeringBeforePhysics.x > 0.0)

        pathFollower.clearPath(entityId)
        val impulseAfterClear = store.impulseVelocity(slot)
        assertEquals(1.0, impulseAfterClear.z, 0.001)
        assertEquals(0.0, store.steeringVelocity(slot).x, 0.001)

        physicsSystem.update(tickContext)

        val positionAfterPhysics = store.position(slot)
        assertTrue(positionAfterPhysics.z > 2.5)

        val impulseAfterPhysics = store.impulseVelocity(slot)
        assertEquals(1.0 * physicsConfig.groundFriction, impulseAfterPhysics.z, 0.001)
    }

    @Test
    fun testMovementVelocityRecordsActualDisplacement() {
        val map = TestSimulatedMapFactory.create()
        val store = SimulatedEntityStore()
        val physicsSystem = SimulatedPhysicsSystem(
            entityStore = store,
            map = map,
            damageConsumer = { _, _, _ -> }
        )

        val entityId = store.create(
            position = SimulatedVector3(2.5, 1.0, 2.5),
            attributes = SimulatedAttributes(movementSpeed = 0.2)
        )
        val slot = store.requireSlot(entityId)
        store.setFlag(slot, SimulatedEntityFlag.ON_GROUND, true)
        store.setSteeringVelocity(slot, 0.2, 0.0)

        val tickContext = SimulatedSystemContext(tick = 1L, deltaSeconds = 0.05)
        physicsSystem.update(tickContext)

        val movement = store.movementVelocity(slot)
        assertTrue(movement.x > 0.0)
        assertEquals(store.position(slot).x - 2.5, movement.x, 0.0001)

        val publishedVelX = DoubleArray(1)
        val publishedVelY = DoubleArray(1)
        val publishedVelZ = DoubleArray(1)
        store.copyFrameStateTo(
            entityIds = IntArray(1),
            positionX = DoubleArray(1),
            positionY = DoubleArray(1),
            positionZ = DoubleArray(1),
            velocityX = publishedVelX,
            velocityY = publishedVelY,
            velocityZ = publishedVelZ,
            yaw = FloatArray(1),
            pitch = FloatArray(1),
            health = DoubleArray(1),
            maximumHealth = DoubleArray(1),
            hitboxWidth = DoubleArray(1),
            hitboxHeight = DoubleArray(1),
            teams = IntArray(1),
            presentationIds = IntArray(1),
            flags = LongArray(1)
        )
        assertEquals(movement.x, publishedVelX[0], 0.0001)
    }
}
