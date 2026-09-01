package zaqws.zycos.simulated.external

import java.util.concurrent.ArrayBlockingQueue

internal class SimulatedExternalActionQueue(
    maximumActions: Int
) {
    private val queue =
        ArrayBlockingQueue<SimulatedExternalAction>(
            maximumActions
        )

    init {
        require(maximumActions > 0)
    }

    fun offer(
        action: SimulatedExternalAction
    ) {
        while (!queue.offer(action)) {
            queue.poll()
        }
    }

    fun drainTo(
        destination:
            MutableCollection<SimulatedExternalAction>,
        maximumActions: Int
    ): Int {
        require(maximumActions >= 0)

        var drainedActions = 0

        while (
            drainedActions <
            maximumActions
        ) {
            val action =
                queue.poll()
                    ?: break

            destination.add(action)
            drainedActions++
        }

        return drainedActions
    }

    fun clear() {
        queue.clear()
    }
}
