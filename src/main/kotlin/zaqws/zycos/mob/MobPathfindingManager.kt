@file:Suppress("unused")

package zaqws.zycos.mob

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Mob
import org.bukkit.scheduler.BukkitTask
import zaqws.zycos.AreaManager
import zaqws.zycos.CoroutineManager.mainDispatcher
import zaqws.zycos.CoroutineManager.scope
import zaqws.zycos.Main
import java.util.*

object MobPathfindingManager {
    private const val NAVIGATION_LEASE_TICKS = 3L
    private const val NO_NAVIGATION_REFRESH_TICK = Long.MIN_VALUE

    private val gridRegistry = PathfindingManager.GridRegistry()
    private val grids = HashMap<String, GridContext>()
    private val mobs = HashMap<UUID, MobContext>()

    private var initialized = false
    private var navigationTask: BukkitTask? = null
    private var navigationTick = 0L

    internal fun register() {
        checkMainThread()
        if (initialized) return

        initialized = true
        navigationTick = 0L
        navigationTask = Bukkit.getScheduler().runTaskTimer(
            Main.plugin,
            Runnable { tickNavigators() },
            1L,
            1L
        )
    }

    internal fun unregister() {
        checkMainThread()

        navigationTask?.cancel()
        navigationTask = null

        for ((_, _, navigator) in mobs.values) {
            navigator.cancel()
        }

        mobs.clear()

        for ((_, grid, listener) in grids.values) {
            listener.unregister()
            grid.clear()
        }

        grids.clear()
        gridRegistry.clear()
        navigationTick = 0L

        initialized = false
    }


    suspend fun registerGrid(
        identifier: String,
        world: World,
        area: AreaManager.Area,
        profile: MobPathfindingProfile = MobPathfindingProfile()
    ) {
        require(identifier.isNotBlank()) {
            "Grid identifier cannot be blank."
        }

        withContext(Main.plugin.mainDispatcher) {
            unregisterGrid(identifier)
            val grid = gridRegistry.registerGrid(
                identifier,
                area,
                profile
            )
            grid.worldId = world.uid
            val listener = PathfindingManager.PathfindingUpdateListener(
                Main.plugin,
                gridRegistry,
                grid
            )
            val context = GridContext(world, grid, listener)
            grids[identifier] = context

            try {
                Main.plugin.server.pluginManager.registerEvents(
                    listener,
                    Main.plugin
                )
                val snapshots = gridRegistry.captureAreaSnapshots(world, area)
                gridRegistry.bakeAll(
                    grid,
                    snapshots,
                    world.minHeight,
                    world.maxHeight
                )
                ensureActive()
                if (!grid.active || grids[identifier] !== context) {
                    throw CancellationException("Grid registration was superseded or unregistered.")
                }
                context.ready = true
            } catch (throwable: Throwable) {
                withContext(NonCancellable + Main.plugin.mainDispatcher) {
                    if (grids[identifier] === context) unregisterGrid(identifier)
                }

                throw throwable
            }
        }
    }

    fun unregisterGrid(identifier: String): Boolean {
        checkMainThread()

        val context = grids.remove(identifier) ?: return false
        val iterator = mobs.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val mobContext = entry.value

            if (mobContext.gridIdentifier != identifier) {
                continue
            }

            mobContext.navigator.cancel()
            iterator.remove()
        }

        context.listener.unregister()
        gridRegistry.removeGrid(identifier)

        return true
    }

    fun register(
        mob: Mob,
        gridIdentifier: String
    ) {
        checkMainThread()

        require(mob.isValid && !mob.isDead) {
            "Cannot register an invalid or dead mob."
        }

        val gridContext = grids[gridIdentifier] ?: throw IllegalArgumentException(
            "Pathfinding grid '$gridIdentifier' is not registered."
        )
        check(gridContext.ready) {
            "Pathfinding grid '$gridIdentifier' is still being registered."
        }

        require(mob.world == gridContext.world) {
            "Mob world does not match pathfinding grid '$gridIdentifier'."
        }

        val existingContext = mobs[mob.uniqueId]
        if (existingContext != null) {
            if (existingContext.gridIdentifier == gridIdentifier) {
                return
            }
        }

        val navigator = PathfindingManager.HierarchicalNavigator(
            entity = mob,
            hierarchicalGrid = gridContext.grid,
            scope = Main.plugin.scope,
            gridRegistry = gridRegistry
        )
        existingContext?.navigator?.cancel()
        mobs[mob.uniqueId] = MobContext(
            gridIdentifier,
            mob,
            navigator
        )
    }

    fun unregister(mob: Mob): Boolean {
        checkMainThread()

        val context = mobs.remove(mob.uniqueId) ?: return false
        context.navigator.cancel()

        return true
    }

    fun navigateTo(
        mob: Mob,
        target: Location,
        speed: Double = 1.0
    ): Boolean = navigate(
        mob,
        target,
        speed,
        NavigationRequestMode.LEASED
    )

    fun navigatePersistentlyTo(
        mob: Mob,
        target: Location,
        speed: Double = 1.0
    ): Boolean = navigate(
        mob,
        target,
        speed,
        NavigationRequestMode.PERSISTENT
    )

    fun stop(mob: Mob): Boolean {
        checkMainThread()

        val context = mobs[mob.uniqueId] ?: return false
        clearNavigationRequest(context)
        context.navigator.cancel()

        return true
    }

    fun hasGrid(identifier: String): Boolean {
        checkMainThread()
        return grids[identifier]?.ready == true
    }

    fun isRegistered(mob: Mob): Boolean {
        checkMainThread()
        return mob.uniqueId in mobs
    }


    private fun navigate(
        mob: Mob,
        target: Location,
        speed: Double,
        requestMode: NavigationRequestMode
    ): Boolean {
        checkMainThread()

        require(speed.isFinite() && speed > 0.0) {
            "Navigation speed must be finite and greater than zero."
        }

        val context = mobs[mob.uniqueId] ?: return false
        if (!mob.isValid || mob.isDead) {
            unregister(mob)
            return false
        }

        if (!context.navigator.navigateTo(target)) {
            clearNavigationRequest(context)
            return false
        }

        context.requestMode = requestMode
        context.lastNavigationRefreshTick = navigationTick
        context.navigator.speed = speed

        return true
    }

    private fun tickNavigators() {
        navigationTick++

        val iterator = mobs.entries.iterator()
        while (iterator.hasNext()) {
            val context = iterator.next().value
            if (!context.mob.isValid || context.mob.isDead) {
                context.navigator.cancel()
                iterator.remove()
                continue
            }

            if (context.requestMode == NavigationRequestMode.LEASED &&
                navigationTick - context.lastNavigationRefreshTick > NAVIGATION_LEASE_TICKS
            ) {
                clearNavigationRequest(context)
                context.navigator.cancel()
                continue
            }

            if (context.requestMode != NavigationRequestMode.NONE) {
                context.navigator.tick()
            }
        }
    }

    private fun clearNavigationRequest(context: MobContext) {
        context.requestMode = NavigationRequestMode.NONE
        context.lastNavigationRefreshTick = NO_NAVIGATION_REFRESH_TICK
    }

    private fun checkMainThread() {
        check(Bukkit.isPrimaryThread()) {
            "MobPathfindingManager must be accessed from the Paper main thread."
        }
    }


    private data class GridContext(
        val world: World,
        val grid: PathfindingManager.HierarchicalGrid,
        val listener: PathfindingManager.PathfindingUpdateListener,
        var ready: Boolean = false
    )

    private enum class NavigationRequestMode {
        NONE,
        LEASED,
        PERSISTENT
    }

    private data class MobContext(
        val gridIdentifier: String,
        val mob: Mob,
        val navigator: PathfindingManager.HierarchicalNavigator,
        var requestMode: NavigationRequestMode = NavigationRequestMode.NONE,
        var lastNavigationRefreshTick: Long = NO_NAVIGATION_REFRESH_TICK
    )

    data class MobPathfindingProfile(
        val mobHeight: Int = 2,
        val mobWidth: Double = 0.6,
        val maxStepUp: Int = 1,
        val maxStepDown: Int = 3
    ) {
        init {
            require(mobHeight > 0)
            require(mobWidth.isFinite() && mobWidth > 0.0)
            require(maxStepUp >= 0)
            require(maxStepDown >= 0)
        }
    }
}
