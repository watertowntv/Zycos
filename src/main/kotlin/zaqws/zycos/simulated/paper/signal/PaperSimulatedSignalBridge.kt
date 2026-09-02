@file:Suppress("unused")

package zaqws.zycos.simulated.paper.signal

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.signal.SimulatedSignalEvent
import java.util.concurrent.atomic.AtomicBoolean

class PaperSimulatedSignalBridge(
    private val plugin: JavaPlugin,
    private val engine: SimulatedEngine,
    private val handler: (SimulatedSignalEvent) -> Unit,
    val maximumSignalsPerTick: Int =
        DEFAULT_MAXIMUM_SIGNALS_PER_TICK
) : AutoCloseable {
    companion object {
        const val DEFAULT_MAXIMUM_SIGNALS_PER_TICK = 8_192
    }

    private val closed = AtomicBoolean()
    private var scheduledTask: BukkitTask? = null

    init {
        require(maximumSignalsPerTick > 0)
    }

    val isRunning: Boolean
        get() = scheduledTask != null

    val isClosed: Boolean
        get() = closed.get()

    fun start(): PaperSimulatedSignalBridge {
        check(!closed.get()) {
            "PaperSimulatedSignalBridge is closed"
        }
        if (scheduledTask != null) return this

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
    }

    fun update() {
        check(!closed.get()) {
            "PaperSimulatedSignalBridge is closed"
        }
        check(plugin.server.isPrimaryThread) {
            "PaperSimulatedSignalBridge.update must be called from the server thread"
        }

        for (
        signal in
        engine.drainSignals(
            maximumSignalsPerTick
        )
        ) {
            handler(signal)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
    }
}
