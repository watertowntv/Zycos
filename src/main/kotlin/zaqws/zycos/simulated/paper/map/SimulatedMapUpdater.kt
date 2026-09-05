@file:Suppress("unused")

package zaqws.zycos.simulated.paper.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import zaqws.zycos.CoroutineManager.asyncDispatcher
import zaqws.zycos.CoroutineManager.asyncScope
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.math.SimulatedBlockPosition
import java.lang.Runnable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SimulatedMapUpdater(
    plugin: JavaPlugin,
    private val map: SimulatedMap,
    private val patchBaker: SimulatedMapPatchBaker,
    updateIntervalTicks: Long =
        DEFAULT_UPDATE_INTERVAL_TICKS,
    private val patchRadiusChunks: Int =
        map.config.patchRadiusChunks
) : Listener, AutoCloseable {
    companion object {
        private const val DEFAULT_UPDATE_INTERVAL_TICKS = 2L
    }

    private val dirtyChunks =
        ConcurrentHashMap.newKeySet<Long>()

    private val updateRunning =
        AtomicBoolean(false)

    private val closed =
        AtomicBoolean(false)

    private val updateMutex =
        Mutex()

    private val scopeJob =
        SupervisorJob(
            plugin.asyncScope
                .coroutineContext[Job]
        )

    private val scope =
        CoroutineScope(
            scopeJob +
                    plugin.asyncDispatcher
        )

    private val scheduledTask: BukkitTask

    init {
        require(updateIntervalTicks > 0L)
        require(patchRadiusChunks >= 0)

        plugin.server.pluginManager
            .registerEvents(
                this,
                plugin
            )

        scheduledTask =
            plugin.server.scheduler
                .runTaskTimer(
                    plugin,
                    Runnable(
                        ::requestUpdate
                    ),
                    updateIntervalTicks,
                    updateIntervalTicks
                )
    }

    fun markBlock(
        blockX: Int,
        blockZ: Int
    ) {
        markChunk(
            blockX shr
                    SimulatedBlockPosition
                        .CHUNK_SHIFT,

            blockZ shr
                    SimulatedBlockPosition
                        .CHUNK_SHIFT
        )
    }

    fun markChunk(
        chunkX: Int,
        chunkZ: Int
    ) {
        if (closed.get()) return

        for (
        offsetZ in
        -patchRadiusChunks..
                patchRadiusChunks
        ) {
            for (
            offsetX in
            -patchRadiusChunks..
                    patchRadiusChunks
            ) {
                val targetChunkX =
                    chunkX + offsetX

                val targetChunkZ =
                    chunkZ + offsetZ

                if (
                    !map.bounds.containsChunk(
                        targetChunkX,
                        targetChunkZ
                    )
                ) {
                    continue
                }

                dirtyChunks.add(
                    SimulatedMap
                        .packChunkCoordinates(
                            targetChunkX,
                            targetChunkZ
                        )
                )
            }
        }
    }

    fun requestUpdate() {
        if (closed.get()) return
        if (dirtyChunks.isEmpty()) return

        if (
            !updateRunning.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        scope.launch {
            var succeeded = false
            try {
                processUpdates()
                succeeded = true
            } finally {
                updateRunning.set(false)

                if (
                    succeeded &&
                    !closed.get() &&
                    dirtyChunks.isNotEmpty()
                ) {
                    requestUpdate()
                }
            }
        }
    }

    suspend fun updateNow() {
        if (closed.get()) return

        processUpdates()
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    fun onBlockPlace(
        event: BlockPlaceEvent
    ) {
        val block = event.block

        markBlock(
            block.x,
            block.z
        )
    }

    @EventHandler(
        priority = EventPriority.MONITOR,
        ignoreCancelled = true
    )
    fun onBlockBreak(
        event: BlockBreakEvent
    ) {
        val block = event.block

        markBlock(
            block.x,
            block.z
        )
    }

    private suspend fun processUpdates() {
        updateMutex.withLock {
            while (!closed.get()) {
                val coordinates =
                    drainDirtyChunks()

                if (coordinates.isEmpty()) {
                    return
                }

                try {
                    val patch =
                        patchBaker.bakeChunks(
                            coordinates
                        ) ?: continue

                    if (closed.get()) {
                        return
                    }

                    map.applyPatch(patch)
                } catch (throwable: Throwable) {
                    if (!closed.get()) {
                        for (coordinate in coordinates) {
                            dirtyChunks.add(
                                SimulatedMap
                                    .packChunkCoordinates(
                                        coordinate.chunkX,
                                        coordinate.chunkZ
                                    )
                            )
                        }
                    }
                    throw throwable
                }
            }
        }
    }

    private fun drainDirtyChunks():
            List<
                    SimulatedMapPatchBaker
                    .ChunkCoordinate
                    > {
        if (dirtyChunks.isEmpty()) {
            return emptyList()
        }

        val packedCoordinates =
            dirtyChunks.toTypedArray()

        val coordinates =
            ArrayList<
                    SimulatedMapPatchBaker
                    .ChunkCoordinate
                    >(
                packedCoordinates.size
            )

        for (
        packedCoordinate in
        packedCoordinates
        ) {
            if (
                !dirtyChunks.remove(
                    packedCoordinate
                )
            ) {
                continue
            }

            coordinates.add(
                SimulatedMapPatchBaker
                    .ChunkCoordinate(
                        chunkX =
                            SimulatedMap
                                .unpackChunkX(
                                    packedCoordinate
                                ),

                        chunkZ =
                            SimulatedMap
                                .unpackChunkZ(
                                    packedCoordinate
                                )
                    )
            )
        }

        return coordinates
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

        scheduledTask.cancel()

        HandlerList.unregisterAll(this)

        dirtyChunks.clear()

        scope.cancel()
    }
}
