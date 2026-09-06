@file:Suppress("unused")

package zaqws.zycos.simulated.paper.player

import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.external.SimulatedExternalFrame
import java.util.concurrent.atomic.AtomicBoolean

class PaperPlayerBridge @JvmOverloads constructor(
    private val plugin: JavaPlugin,
    private val engine: SimulatedEngine,
    val playerProvider: PaperPlayerProvider = PaperPlayerProvider(plugin),
    private val actionHandler: PaperExternalActionHandler =
        PaperExternalActionHandler(plugin, playerProvider, playerProvider.world),
    val maximumActionsPerTick: Int = DEFAULT_MAXIMUM_ACTIONS_PER_TICK
) : AutoCloseable {
    @JvmOverloads
    constructor(
        plugin: JavaPlugin,
        engine: SimulatedEngine,
        world: World?,
        teamResolver: PaperPlayerTeamResolver = PaperPlayerTeamResolver.NONE,
        maximumActionsPerTick: Int = DEFAULT_MAXIMUM_ACTIONS_PER_TICK
    ) : this(
        plugin = plugin,
        engine = engine,
        playerProvider = PaperPlayerProvider(plugin, teamResolver, world),
        maximumActionsPerTick = maximumActionsPerTick
    )

    val world: World?
        get() = playerProvider.world
    companion object {
        const val DEFAULT_MAXIMUM_ACTIONS_PER_TICK = 8_192
    }

    private val closed = AtomicBoolean()
    private var scheduledTask: BukkitTask? = null
    private var sequence = 0L

    init {
        require(maximumActionsPerTick > 0)
    }

    val isRunning: Boolean
        get() = scheduledTask != null

    val isClosed: Boolean
        get() = closed.get()

    fun start(): PaperPlayerBridge {
        check(!closed.get()) { "PaperPlayerBridge is closed" }
        if (scheduledTask != null) return this

        scheduledTask = plugin.server.scheduler.runTaskTimer(
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

        if (engine.isOperational && sequence < Long.MAX_VALUE) {
            engine.submitExternalFrame(
                SimulatedExternalFrame(sequence++, emptyList())
            )
        }
    }

    fun update() {
        check(!closed.get()) { "PaperPlayerBridge is closed" }
        check(plugin.server.isPrimaryThread) {
            "PaperPlayerBridge.update must be called from the server thread"
        }
        if (!engine.isOperational) {
            stop()
            return
        }
        check(sequence < Long.MAX_VALUE) {
            "Paper player frame sequence space exhausted"
        }

        engine.submitExternalFrame(playerProvider.capture(sequence++))
        actionHandler.handleAll(engine.drainExternalActions(maximumActionsPerTick))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
    }
}
