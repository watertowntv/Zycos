@file:Suppress("unused")

package zaqws.zycos.mob

import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Mob
import org.bukkit.event.HandlerList
import zaqws.zycos.AreaManager
import zaqws.zycos.CoroutineManager.mainDispatcher
import zaqws.zycos.CoroutineManager.scope
import zaqws.zycos.Main
import java.util.*

object MobPathfindingManager {
    private val gridRegistry = PathfindingManager.GridRegistry()
    private val grids = HashMap<String, GridContext>()
    private val mobs = HashMap<UUID, MobContext>()

    private var initialized = false

    internal fun register() {
        checkMainThread()

        initialized = true
    }

    internal fun unregister() {
        if (!initialized) return

        checkMainThread()

        for ((_, navigator) in mobs.values) {
            navigator.cancel()
        }

        mobs.clear()

        for ((_, grid, listener) in grids.values) {
            HandlerList.unregisterAll(listener)
            grid.clear()
        }

        grids.clear()
        gridRegistry.clear()

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

        unregisterGrid(identifier)

        val grid = withContext(Main.plugin.mainDispatcher) {
            gridRegistry.registerGrid(
                identifier,
                area,
                profile
            )
        }

        try {
            val snapshots = withContext(Main.plugin.mainDispatcher) {
                gridRegistry.captureAreaSnapshots(world, area)
            }

            gridRegistry.bakeAll(
                grid,
                snapshots,
                world.minHeight,
                world.maxHeight
            )

            withContext(Main.plugin.mainDispatcher) {
                val listener = PathfindingManager.PathfindingUpdateListener(
                    Main.plugin,
                    gridRegistry,
                    grid
                )

                Main.plugin.server.pluginManager.registerEvents(
                    listener,
                    Main.plugin
                )

                grids[identifier] = GridContext(
                    world,
                    grid,
                    listener
                )
            }
        } catch (throwable: Throwable) {
            withContext(Main.plugin.mainDispatcher) {
                gridRegistry.removeGrid(identifier)?.clear()
            }

            throw throwable
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

        HandlerList.unregisterAll(context.listener)
        gridRegistry.removeGrid(identifier)?.clear()

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

        require(mob.world == gridContext.world) {
            "Mob world does not match pathfinding grid '$gridIdentifier'."
        }

        val existingContext = mobs[mob.uniqueId]
        if (existingContext != null) {
            if (existingContext.gridIdentifier == gridIdentifier) {
                return
            }

            existingContext.navigator.cancel()
        }

        mobs[mob.uniqueId] = MobContext(
            gridIdentifier,
            PathfindingManager.HierarchicalNavigator(
                plugin = Main.plugin,
                entity = mob,
                hierarchicalGrid = gridContext.grid,
                scope = Main.plugin.scope,
                gridRegistry = gridRegistry
            )
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
    ): Boolean {
        checkMainThread()

        require(speed > 0.0) {
            "Navigation speed must be greater than zero."
        }

        val context = mobs[mob.uniqueId] ?: return false
        if (!mob.isValid || mob.isDead) {
            unregister(mob)
            return false
        }

        context.navigator.speed = speed
        context.navigator.navigateTo(target)

        return true
    }

    fun stop(mob: Mob): Boolean {
        checkMainThread()

        val context = mobs[mob.uniqueId] ?: return false
        context.navigator.cancel()

        return true
    }

    fun hasGrid(identifier: String): Boolean = identifier in grids
    fun isRegistered(mob: Mob): Boolean = mob.uniqueId in mobs

    private fun checkMainThread() {
        check(Bukkit.isPrimaryThread()) {
            "MobPathfindingManager must be accessed from the Paper main thread."
        }
    }


    private data class GridContext(
        val world: World,
        val grid: PathfindingManager.HierarchicalGrid,
        val listener: PathfindingManager.PathfindingUpdateListener
    )

    private data class MobContext(
        val gridIdentifier: String,
        val navigator: PathfindingManager.HierarchicalNavigator
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