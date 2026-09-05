@file:Suppress("unused")

package zaqws.zycos.simulated.snapshot

import zaqws.zycos.simulated.entity.SimulatedEntityFlags
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.math.SimulatedVector3
import java.util.concurrent.atomic.AtomicReference

class SimulatedFrame internal constructor(
    val tick: Long,
    internal val rawEntityIds: IntArray,
    internal val rawPositionX: DoubleArray,
    internal val rawPositionY: DoubleArray,
    internal val rawPositionZ: DoubleArray,
    internal val rawVelocityX: DoubleArray,
    internal val rawVelocityY: DoubleArray,
    internal val rawVelocityZ: DoubleArray,
    internal val rawYaw: FloatArray,
    internal val rawPitch: FloatArray,
    internal val rawHealth: DoubleArray,
    internal val rawMaximumHealth: DoubleArray,
    internal val rawHitboxWidth: DoubleArray,
    internal val rawHitboxHeight: DoubleArray,
    internal val rawTeams: IntArray,
    internal val rawPresentationIds: IntArray,
    internal val rawFlags: LongArray
) {
    companion object {
        val EMPTY = SimulatedFrame(
            tick = 0L,
            rawEntityIds = IntArray(0),
            rawPositionX = DoubleArray(0),
            rawPositionY = DoubleArray(0),
            rawPositionZ = DoubleArray(0),
            rawVelocityX = DoubleArray(0),
            rawVelocityY = DoubleArray(0),
            rawVelocityZ = DoubleArray(0),
            rawYaw = FloatArray(0),
            rawPitch = FloatArray(0),
            rawHealth = DoubleArray(0),
            rawMaximumHealth = DoubleArray(0),
            rawHitboxWidth = DoubleArray(0),
            rawHitboxHeight = DoubleArray(0),
            rawTeams = IntArray(0),
            rawPresentationIds = IntArray(0),
            rawFlags = LongArray(0)
        )
    }

    val size: Int
        get() = rawEntityIds.size

    val entityIds: IntArray
        get() = rawEntityIds.clone()

    val positionX: DoubleArray
        get() = rawPositionX.clone()

    val positionY: DoubleArray
        get() = rawPositionY.clone()

    val positionZ: DoubleArray
        get() = rawPositionZ.clone()

    val velocityX: DoubleArray
        get() = rawVelocityX.clone()

    val velocityY: DoubleArray
        get() = rawVelocityY.clone()

    val velocityZ: DoubleArray
        get() = rawVelocityZ.clone()

    val yaw: FloatArray
        get() = rawYaw.clone()

    val pitch: FloatArray
        get() = rawPitch.clone()

    val health: DoubleArray
        get() = rawHealth.clone()

    val maximumHealth: DoubleArray
        get() = rawMaximumHealth.clone()

    val hitboxWidth: DoubleArray
        get() = rawHitboxWidth.clone()

    val hitboxHeight: DoubleArray
        get() = rawHitboxHeight.clone()

    val teams: IntArray
        get() = rawTeams.clone()

    val presentationIds: IntArray
        get() = rawPresentationIds.clone()

    val flags: LongArray
        get() = rawFlags.clone()

    init {
        require(rawPositionX.size == size)
        require(rawPositionY.size == size)
        require(rawPositionZ.size == size)

        require(rawVelocityX.size == size)
        require(rawVelocityY.size == size)
        require(rawVelocityZ.size == size)

        require(rawYaw.size == size)
        require(rawPitch.size == size)

        require(rawHealth.size == size)
        require(rawMaximumHealth.size == size)

        require(rawHitboxWidth.size == size)
        require(rawHitboxHeight.size == size)

        require(rawTeams.size == size)
        require(rawPresentationIds.size == size)
        require(rawFlags.size == size)
    }

    fun entityIdAt(index: Int): SimulatedEntityId {
        requireValidIndex(index)

        return SimulatedEntityId(rawEntityIds[index])
    }

    fun positionAt(index: Int): SimulatedVector3 {
        requireValidIndex(index)

        return SimulatedVector3(
            rawPositionX[index],
            rawPositionY[index],
            rawPositionZ[index]
        )
    }

    fun velocityAt(index: Int): SimulatedVector3 {
        requireValidIndex(index)

        return SimulatedVector3(
            rawVelocityX[index],
            rawVelocityY[index],
            rawVelocityZ[index]
        )
    }

    fun teamAt(index: Int): SimulatedTeam {
        requireValidIndex(index)

        return SimulatedTeam(rawTeams[index])
    }

    fun presentationIdAt(index: Int): SimulatedPresentationId {
        requireValidIndex(index)

        return SimulatedPresentationId(rawPresentationIds[index])
    }

    fun flagsAt(index: Int): SimulatedEntityFlags {
        requireValidIndex(index)

        return SimulatedEntityFlags(rawFlags[index])
    }

    fun rawEntityIdAt(index: Int): Int {
        requireValidIndex(index)
        return rawEntityIds[index]
    }

    fun positionXAt(index: Int): Double {
        requireValidIndex(index)
        return rawPositionX[index]
    }

    fun positionYAt(index: Int): Double {
        requireValidIndex(index)
        return rawPositionY[index]
    }

    fun positionZAt(index: Int): Double {
        requireValidIndex(index)
        return rawPositionZ[index]
    }

    fun yawAt(index: Int): Float {
        requireValidIndex(index)
        return rawYaw[index]
    }

    fun pitchAt(index: Int): Float {
        requireValidIndex(index)
        return rawPitch[index]
    }

    fun healthAt(index: Int): Double {
        requireValidIndex(index)
        return rawHealth[index]
    }

    fun maximumHealthAt(index: Int): Double {
        requireValidIndex(index)
        return rawMaximumHealth[index]
    }

    fun hitboxWidthAt(index: Int): Double {
        requireValidIndex(index)
        return rawHitboxWidth[index]
    }

    fun hitboxHeightAt(index: Int): Double {
        requireValidIndex(index)
        return rawHitboxHeight[index]
    }

    inline fun forEachIndex(action: (index: Int) -> Unit) {
        var index = 0

        while (index < size) {
            action(index)
            index++
        }
    }

    private fun requireValidIndex(index: Int) {
        require(index in rawEntityIds.indices) {
            "Invalid simulated frame entity index: $index"
        }
    }
}

internal class SimulatedFramePublisher {
    private val reference = AtomicReference(SimulatedFrame.EMPTY)

    val latest: SimulatedFrame
        get() = reference.get()

    fun publish(frame: SimulatedFrame) {
        reference.set(frame)
    }

    fun clear() {
        reference.set(SimulatedFrame.EMPTY)
    }
}
