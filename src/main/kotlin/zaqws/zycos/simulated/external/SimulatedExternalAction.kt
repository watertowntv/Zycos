package zaqws.zycos.simulated.external

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.math.SimulatedVector3
import java.util.concurrent.ArrayBlockingQueue

sealed interface SimulatedExternalAction {
    val actorId: SimulatedExternalActorId

    data class Damage(
        override val actorId: SimulatedExternalActorId,
        val amount: Double,
        val sourceEntityId: SimulatedEntityId? = null
    ) : SimulatedExternalAction {
        init {
            require(amount.isFinite())
            require(amount >= 0.0)
        }
    }

    data class Knockback(
        override val actorId: SimulatedExternalActorId,
        val velocity: SimulatedVector3,
        val sourceEntityId: SimulatedEntityId? = null
    ) : SimulatedExternalAction {
        init {
            require(velocity.isFinite)
        }
    }

    data class Combined(
        override val actorId: SimulatedExternalActorId,
        val damage: Double,
        val knockbackVelocity: SimulatedVector3? = null,
        val sourceEntityId: SimulatedEntityId? = null
    ) : SimulatedExternalAction {
        init {
            require(damage.isFinite())
            require(damage >= 0.0)
            if (knockbackVelocity != null) {
                require(knockbackVelocity.isFinite)
            }
        }
    }
}

internal class SimulatedExternalActionQueue(maximumActions: Int) {
    private val queue = ArrayBlockingQueue<SimulatedExternalAction>(maximumActions)
    private val droppedActionCount = java.util.concurrent.atomic.AtomicLong(0L)

    val droppedCount: Long
        get() = droppedActionCount.get()

    init {
        require(maximumActions > 0)
    }

    fun offer(action: SimulatedExternalAction) {
        while (!queue.offer(action)) {
            if (queue.poll() != null) {
                droppedActionCount.incrementAndGet()
            }
        }
    }

    fun drainTo(
        destination: MutableCollection<SimulatedExternalAction>,
        maximumActions: Int
    ): Int {
        require(maximumActions >= 0)

        var drainedActions = 0

        while (drainedActions < maximumActions) {
            destination.add(queue.poll() ?: break)
            drainedActions++
        }

        return drainedActions
    }

    fun clear() {
        queue.clear()
    }
}
