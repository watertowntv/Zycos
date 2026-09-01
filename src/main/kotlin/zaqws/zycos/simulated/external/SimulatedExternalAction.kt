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

}

internal class SimulatedExternalActionQueue(maximumActions: Int) {
    private val queue = ArrayBlockingQueue<SimulatedExternalAction>(maximumActions)

    init {
        require(maximumActions > 0)
    }

    fun offer(action: SimulatedExternalAction) {
        while (!queue.offer(action)) queue.poll()
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
