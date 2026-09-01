@file:Suppress("unused")

package zaqws.zycos.simulated.paper.player

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import java.util.concurrent.atomic.AtomicBoolean

class PaperPlayerBridge(
    private val plugin: JavaPlugin,
    private val engine: SimulatedEngine,
    val playerProvider: PaperPlayerProvider =
        PaperPlayerProvider(plugin),
    private val actionHandler:
        PaperExternalActionHandler =
        PaperExternalActionHandler(
            plugin,
            playerProvider
        ),
    val maximumActionsPerTick: Int =
        DEFAULT_MAXIMUM_ACTIONS_PER_TICK
) : AutoCloseable {
    private val closed =
        AtomicBoolean(false)

    private var scheduledTask:
            BukkitTask? = null

    private var sequence = 0L

    init {
        require(maximumActionsPerTick > 0)
    }

    val isRunning: Boolean
        get() = scheduledTask != null

    val isClosed: Boolean
        get() = closed.get()

    fun start(): PaperPlayerBridge {
        check(!closed.get()) {
            "PaperPlayerBridge is closed"
        }

        if (scheduledTask != null) {
            return this
        }

        scheduledTask =
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable(::update),
                1L,
                1L
            )

        return this
    }

    fun stop() {
        scheduledTask?.cancel()
        scheduledTask = null

        if (
            !engine.isClosed &&
            sequence < Long.MAX_VALUE
        ) {
            engine.submitExternalFrame(
                SimulatedExternalFrame(
                    sequence = sequence++,
                    actors = emptyList()
                )
            )
        }
    }

    fun update() {
        check(!closed.get()) {
            "PaperPlayerBridge is closed"
        }

        check(plugin.server.isPrimaryThread) {
            "PaperPlayerBridge.update must be called from the server thread"
        }

        check(sequence < Long.MAX_VALUE) {
            "Paper player frame sequence space exhausted"
        }

        engine.submitExternalFrame(
            playerProvider.capture(
                sequence++
            )
        )

        actionHandler.handleAll(
            engine.drainExternalActions(
                maximumActionsPerTick
            )
        )
    }

    override fun close() {
        if (
            !closed.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        stop()
    }

    companion object {
        const val DEFAULT_MAXIMUM_ACTIONS_PER_TICK =
            8_192
    }
}
