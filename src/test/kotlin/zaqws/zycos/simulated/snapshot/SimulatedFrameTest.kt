package zaqws.zycos.simulated.snapshot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import zaqws.zycos.simulated.entity.SimulatedEntityFlags
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.math.SimulatedVector3

class SimulatedFrameTest {
    @Test
    fun `detached getters return clones without mutating frame data`() {
        val rawEntityIds = intArrayOf(42)
        val rawPositionX = doubleArrayOf(10.0)
        val rawPositionY = doubleArrayOf(20.0)
        val rawPositionZ = doubleArrayOf(30.0)
        val rawVelocityX = doubleArrayOf(0.1)
        val rawVelocityY = doubleArrayOf(0.2)
        val rawVelocityZ = doubleArrayOf(0.3)
        val rawYaw = floatArrayOf(90.0f)
        val rawPitch = floatArrayOf(45.0f)
        val rawHealth = doubleArrayOf(20.0)
        val rawMaximumHealth = doubleArrayOf(20.0)
        val rawHitboxWidth = doubleArrayOf(0.6)
        val rawHitboxHeight = doubleArrayOf(1.8)
        val rawTeams = intArrayOf(1)
        val rawPresentationIds = intArrayOf(2)
        val rawFlags = longArrayOf(0L)

        val frame = SimulatedFrame(
            tick = 100L,
            rawEntityIds = rawEntityIds,
            rawPositionX = rawPositionX,
            rawPositionY = rawPositionY,
            rawPositionZ = rawPositionZ,
            rawVelocityX = rawVelocityX,
            rawVelocityY = rawVelocityY,
            rawVelocityZ = rawVelocityZ,
            rawYaw = rawYaw,
            rawPitch = rawPitch,
            rawHealth = rawHealth,
            rawMaximumHealth = rawMaximumHealth,
            rawHitboxWidth = rawHitboxWidth,
            rawHitboxHeight = rawHitboxHeight,
            rawTeams = rawTeams,
            rawPresentationIds = rawPresentationIds,
            rawFlags = rawFlags
        )

        assertEquals(1, frame.size)
        assertEquals(42, frame.rawEntityIdAt(0))
        assertEquals(SimulatedEntityId(42), frame.entityIdAt(0))
        assertEquals(SimulatedVector3(10.0, 20.0, 30.0), frame.positionAt(0))
        assertEquals(10.0, frame.positionXAt(0))

        val detachedIds = frame.entityIds
        assertNotSame(detachedIds, frame.rawEntityIds)
        detachedIds[0] = 999
        assertEquals(42, frame.rawEntityIdAt(0))
        assertEquals(42, frame.entityIds[0])

        val detachedPosX = frame.positionX
        detachedPosX[0] = 999.0
        assertEquals(10.0, frame.positionXAt(0))
        assertEquals(10.0, frame.positionX[0])
    }
}
