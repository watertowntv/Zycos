package zaqws.zycos.simulated.snapshot

import zaqws.zycos.simulated.entity.SimulatedEntityFlags
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.entity.SimulatedTeam
import zaqws.zycos.simulated.math.SimulatedVector3
import java.util.concurrent.atomic.AtomicReference

class SimulatedFrame internal constructor(
    val tick: Long,
    val entityIds: IntArray,
    val positionX: DoubleArray,
    val positionY: DoubleArray,
    val positionZ: DoubleArray,
    val velocityX: DoubleArray,
    val velocityY: DoubleArray,
    val velocityZ: DoubleArray,
    val yaw: FloatArray,
    val pitch: FloatArray,
    val health: DoubleArray,
    val maximumHealth: DoubleArray,
    val hitboxWidth: DoubleArray,
    val hitboxHeight: DoubleArray,
    val teams: IntArray,
    val presentationIds: IntArray,
    val flags: LongArray
) {
    companion object {
        val EMPTY = SimulatedFrame(
            tick = 0L,
            entityIds = IntArray(0),
            positionX = DoubleArray(0),
            positionY = DoubleArray(0),
            positionZ = DoubleArray(0),
            velocityX = DoubleArray(0),
            velocityY = DoubleArray(0),
            velocityZ = DoubleArray(0),
            yaw = FloatArray(0),
            pitch = FloatArray(0),
            health = DoubleArray(0),
            maximumHealth = DoubleArray(0),
            hitboxWidth = DoubleArray(0),
            hitboxHeight = DoubleArray(0),
            teams = IntArray(0),
            presentationIds = IntArray(0),
            flags = LongArray(0)
        )
    }

    val size: Int
        get() = entityIds.size

    init {
        require(positionX.size == size)
        require(positionY.size == size)
        require(positionZ.size == size)

        require(velocityX.size == size)
        require(velocityY.size == size)
        require(velocityZ.size == size)

        require(yaw.size == size)
        require(pitch.size == size)

        require(health.size == size)
        require(maximumHealth.size == size)

        require(hitboxWidth.size == size)
        require(hitboxHeight.size == size)

        require(teams.size == size)
        require(presentationIds.size == size)
        require(flags.size == size)
    }

    fun entityIdAt(index: Int): SimulatedEntityId {
        requireValidIndex(index)

        return SimulatedEntityId(entityIds[index])
    }

    fun positionAt(index: Int): SimulatedVector3 {
        requireValidIndex(index)

        return SimulatedVector3(
            positionX[index],
            positionY[index],
            positionZ[index]
        )
    }

    fun velocityAt(index: Int): SimulatedVector3 {
        requireValidIndex(index)

        return SimulatedVector3(
            velocityX[index],
            velocityY[index],
            velocityZ[index]
        )
    }

    fun teamAt(index: Int): SimulatedTeam {
        requireValidIndex(index)

        return SimulatedTeam(teams[index])
    }

    fun presentationIdAt(index: Int): SimulatedPresentationId {
        requireValidIndex(index)

        return SimulatedPresentationId(presentationIds[index])
    }

    fun flagsAt(index: Int): SimulatedEntityFlags {
        requireValidIndex(index)

        return SimulatedEntityFlags(flags[index])
    }

    inline fun forEachIndex(action: (index: Int) -> Unit) {
        var index = 0

        while (index < size) {
            action(index)
            index++
        }
    }

    private fun requireValidIndex(index: Int) {
        require(index in entityIds.indices) {
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
