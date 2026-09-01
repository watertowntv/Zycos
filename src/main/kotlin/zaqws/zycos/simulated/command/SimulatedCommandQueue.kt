package zaqws.zycos.simulated.command

import java.util.concurrent.ConcurrentLinkedQueue

internal class SimulatedCommandQueue {
    private val queue = ConcurrentLinkedQueue<SimulatedCommand>()

    val isEmpty: Boolean
        get() = queue.isEmpty()

    fun offer(command: SimulatedCommand) {
        queue.offer(command)
    }

    fun drain(
        maximumCommands: Int = Int.MAX_VALUE,
        consumer: (SimulatedCommand) -> Unit
    ): Int {
        require(maximumCommands >= 0)

        var processedCommands = 0

        while (processedCommands < maximumCommands) {
            val command = queue.poll() ?: break

            consumer(command)
            processedCommands++
        }

        return processedCommands
    }

    fun clear() {
        queue.clear()
    }
}