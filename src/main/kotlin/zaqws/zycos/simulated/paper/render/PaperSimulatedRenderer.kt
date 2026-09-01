@file:Suppress("unused")

package zaqws.zycos.simulated.paper.render

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import zaqws.zycos.ClientEntityManager
import zaqws.zycos.simulated.SimulatedEngine
import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.entity.SimulatedPresentationId
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.snapshot.SimulatedFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class PaperSimulatedRenderer(
    private val plugin: JavaPlugin,
    private val world: World,
    private val engine: SimulatedEngine,
    private val presentationRegistry: SimulatedPresentationRegistry,
    val showRadius: Double = DEFAULT_SHOW_RADIUS,
    val hideRadius: Double = DEFAULT_HIDE_RADIUS
) : AutoCloseable {
    private data class RenderedEntity(
        val clientEntity: ClientEntityManager.ClientEntity,
        val presentationId: SimulatedPresentationId
    )

    private val renderedEntities =
        Int2ObjectOpenHashMap<RenderedEntity>()

    private val currentEntityIds =
        IntOpenHashSet()

    private val frameIndexByEntityId =
        Int2IntOpenHashMap().apply {
            defaultReturnValue(-1)
        }

    private val frameCells =
        Long2ObjectOpenHashMap<IntArrayList>()

    private val candidateIndices =
        IntOpenHashSet()

    private val activePlayers =
        ArrayList<Player>()

    private val closed =
        AtomicBoolean(false)

    private val visualEventHandler =
        PaperVisualEventHandler(
            presentationRegistry =
                presentationRegistry,

            clientEntityProvider = { entityId ->
                renderedEntities[
                    entityId.value
                ]?.clientEntity
            },

            presentationIdProvider = { entityId ->
                renderedEntities[
                    entityId.value
                ]?.presentationId
            }
        )

    private var scheduledTask:
            BukkitTask? = null

    init {
        require(showRadius.isFinite())
        require(hideRadius.isFinite())

        require(showRadius >= 0.0)
        require(hideRadius >= showRadius)
    }

    val renderedEntityCount: Int
        get() =
            renderedEntities.size

    val isRunning: Boolean
        get() =
            scheduledTask != null

    val isClosed: Boolean
        get() =
            closed.get()

    fun start(): PaperSimulatedRenderer {
        check(!closed.get()) {
            "PaperSimulatedRenderer is closed"
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

        removeAll()
    }

    fun update() {
        check(!closed.get()) {
            "PaperSimulatedRenderer is closed"
        }

        check(plugin.server.isPrimaryThread) {
            "PaperSimulatedRenderer.update must be called from the server thread"
        }

        val frame =
            engine.latestFrame

        collectActivePlayers()

        rebuildFrameIndex(frame)
        collectCandidateIndices()

        val renderedIterator =
            renderedEntities
                .keys
                .iterator()

        while (renderedIterator.hasNext()) {
            val frameIndex =
                frameIndexByEntityId.get(
                    renderedIterator.nextInt()
                )

            if (frameIndex >= 0) {
                candidateIndices.add(
                    frameIndex
                )
            }
        }

        val candidateIterator =
            candidateIndices.iterator()

        while (candidateIterator.hasNext()) {
            val index =
                candidateIterator.nextInt()

            updateEntity(
                frame,
                index,
                frame.entityIds[index]
            )
        }

        processVisualEvents()
        removeStaleEntities()
    }

    fun clientEntity(
        entityId: SimulatedEntityId
    ): ClientEntityManager.ClientEntity? =
        renderedEntities[
            entityId.value
        ]?.clientEntity

    fun isRendered(
        entityId: SimulatedEntityId
    ): Boolean =
        renderedEntities.containsKey(
            entityId.value
        )

    fun remove(
        entityId: SimulatedEntityId
    ) {
        val renderedEntity =
            renderedEntities.remove(
                entityId.value
            ) ?: return

        renderedEntity.clientEntity.remove()
    }

    fun removeAll() {
        val iterator =
            renderedEntities
                .values
                .iterator()

        while (iterator.hasNext()) {
            iterator.next()
                .clientEntity
                .remove()
        }

        renderedEntities.clear()
        currentEntityIds.clear()
        activePlayers.clear()
        frameIndexByEntityId.clear()
        frameCells.clear()
        candidateIndices.clear()
    }

    private fun updateEntity(
        frame: SimulatedFrame,
        index: Int,
        entityIdValue: Int
    ) {
        val presentationId =
            frame.presentationIdAt(
                index
            )

        if (
            presentationId ==
            SimulatedPresentationId.NONE
        ) {
            removeRenderedEntity(
                entityIdValue
            )

            return
        }

        val presentation =
            presentationRegistry[
                presentationId
            ]

        if (presentation == null) {
            removeRenderedEntity(
                entityIdValue
            )

            return
        }

        val positionX =
            frame.positionX[index]

        val positionY =
            frame.positionY[index]

        val positionZ =
            frame.positionZ[index]

        var renderedEntity =
            renderedEntities[
                entityIdValue
            ]

        if (
            renderedEntity != null &&
            renderedEntity.presentationId !=
            presentationId
        ) {
            renderedEntity.clientEntity.remove()

            renderedEntities.remove(
                entityIdValue
            )

            renderedEntity = null
        }

        if (
            renderedEntity == null &&
            hasPotentialViewer(
                positionX,
                positionY,
                positionZ
            )
        ) {
            renderedEntity =
                createRenderedEntity(
                    frame =
                        frame,

                    index =
                        index,

                    presentationId =
                        presentationId,

                    presentation =
                        presentation
                )

            renderedEntities.put(
                entityIdValue,
                renderedEntity
            )
        }

        if (renderedEntity == null) {
            return
        }

        val clientEntity =
            renderedEntity.clientEntity

        clientEntity.teleport(
            Location(
                world,
                positionX,
                positionY,
                positionZ,
                frame.yaw[index],
                frame.pitch[index]
            )
        )

        updateViewers(
            clientEntity =
                clientEntity,

            positionX =
                positionX,

            positionY =
                positionY,

            positionZ =
                positionZ
        )

        if (clientEntity.viewers.isEmpty()) {
            clientEntity.remove()

            renderedEntities.remove(
                entityIdValue
            )
        }
    }

    private fun createRenderedEntity(
        frame: SimulatedFrame,
        index: Int,
        presentationId: SimulatedPresentationId,
        presentation: SimulatedPresentation
    ): RenderedEntity {
        val location =
            Location(
                world,
                frame.positionX[index],
                frame.positionY[index],
                frame.positionZ[index],
                frame.yaw[index],
                frame.pitch[index]
            )

        val clientEntity =
            ClientEntityManager.spawn(
                presentation.entityType,
                location
            )

        presentation.onCreate(
            clientEntity
        )

        return RenderedEntity(
            clientEntity =
                clientEntity,

            presentationId =
                presentationId
        )
    }

    private fun updateViewers(
        clientEntity: ClientEntityManager.ClientEntity,
        positionX: Double,
        positionY: Double,
        positionZ: Double
    ) {
        val showRadiusSquared =
            showRadius *
                    showRadius

        val hideRadiusSquared =
            hideRadius *
                    hideRadius

        for (player in activePlayers) {
            val location =
                player.location

            val differenceX =
                location.x -
                        positionX

            val differenceY =
                location.y -
                        positionY

            val differenceZ =
                location.z -
                        positionZ

            val distanceSquared =
                differenceX *
                        differenceX +
                        differenceY *
                        differenceY +
                        differenceZ *
                        differenceZ

            val alreadyViewing =
                player in
                        clientEntity.viewers

            if (alreadyViewing) {
                if (
                    distanceSquared >
                    hideRadiusSquared
                ) {
                    clientEntity.hide(
                        player
                    )
                }
            } else if (
                distanceSquared <=
                showRadiusSquared
            ) {
                clientEntity.show(
                    player
                )
            }
        }

        if (clientEntity.viewers.isEmpty()) {
            return
        }

        val viewers =
            clientEntity.viewers
                .toList()

        for (player in viewers) {
            if (
                !player.isOnline ||
                player.world != world
            ) {
                clientEntity.hide(
                    player
                )
            }
        }
    }

    private fun hasPotentialViewer(
        positionX: Double,
        positionY: Double,
        positionZ: Double
    ): Boolean {
        val showRadiusSquared =
            showRadius *
                    showRadius

        for (player in activePlayers) {
            val location =
                player.location

            val differenceX =
                location.x -
                        positionX

            val differenceY =
                location.y -
                        positionY

            val differenceZ =
                location.z -
                        positionZ

            if (
                differenceX *
                differenceX +
                differenceY *
                differenceY +
                differenceZ *
                differenceZ <=
                showRadiusSquared
            ) {
                return true
            }
        }

        return false
    }

    private fun collectActivePlayers() {
        activePlayers.clear()

        for (
        player in
        plugin.server.onlinePlayers
        ) {
            if (
                player.world != world ||
                !player.isOnline
            ) {
                continue
            }

            activePlayers.add(
                player
            )
        }
    }

    private fun rebuildFrameIndex(
        frame: SimulatedFrame
    ) {
        currentEntityIds.clear()
        frameIndexByEntityId.clear()
        frameCells.clear()

        var index = 0

        while (index < frame.size) {
            val entityId =
                frame.entityIds[index]

            currentEntityIds.add(entityId)

            frameIndexByEntityId.put(
                entityId,
                index
            )

            val cellX =
                SimulatedMath.floorToInt(
                    frame.positionX[index] /
                            FRAME_CELL_SIZE
                )

            val cellZ =
                SimulatedMath.floorToInt(
                    frame.positionZ[index] /
                            FRAME_CELL_SIZE
                )

            frameCells
                .computeIfAbsent(
                    packCell(
                        cellX,
                        cellZ
                    )
                ) {
                    IntArrayList()
                }
                .add(index)

            index++
        }
    }

    private fun collectCandidateIndices() {
        candidateIndices.clear()

        val radiusInCells =
            ceil(
                hideRadius /
                        FRAME_CELL_SIZE
            ).toInt()

        for (player in activePlayers) {
            val location =
                player.location

            val centerCellX =
                SimulatedMath.floorToInt(
                    location.x /
                            FRAME_CELL_SIZE
                )

            val centerCellZ =
                SimulatedMath.floorToInt(
                    location.z /
                            FRAME_CELL_SIZE
                )

            var cellZ =
                centerCellZ -
                        radiusInCells

            while (
                cellZ <=
                centerCellZ + radiusInCells
            ) {
                var cellX =
                    centerCellX -
                            radiusInCells

                while (
                    cellX <=
                    centerCellX + radiusInCells
                ) {
                    val indices =
                        frameCells[
                            packCell(
                                cellX,
                                cellZ
                            )
                        ]

                    if (indices != null) {
                        candidateIndices.addAll(
                            indices
                        )
                    }

                    cellX++
                }

                cellZ++
            }
        }
    }

    private fun packCell(
        cellX: Int,
        cellZ: Int
    ): Long =
        (cellX.toLong() shl Int.SIZE_BITS) or
                (
                        cellZ.toLong() and
                                0xFFFF_FFFFL
                        )

    private fun processVisualEvents() {
        engine.drainVisualEvents(
            maximumEvents =
                MAXIMUM_VISUAL_EVENTS_PER_TICK
        ).forEach(
            visualEventHandler::handle
        )
    }

    private fun removeStaleEntities() {
        val iterator =
            renderedEntities
                .int2ObjectEntrySet()
                .fastIterator()

        while (iterator.hasNext()) {
            val entry =
                iterator.next()

            if (
                currentEntityIds.contains(
                    entry.intKey
                )
            ) {
                continue
            }

            entry.value
                .clientEntity
                .remove()

            iterator.remove()
        }
    }

    private fun removeRenderedEntity(
        entityIdValue: Int
    ) {
        val renderedEntity =
            renderedEntities.remove(
                entityIdValue
            ) ?: return

        renderedEntity.clientEntity.remove()
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

        scheduledTask?.cancel()
        scheduledTask = null

        removeAll()
    }

    companion object {
        const val DEFAULT_SHOW_RADIUS =
            96.0

        const val DEFAULT_HIDE_RADIUS =
            106.0

        private const val
                MAXIMUM_VISUAL_EVENTS_PER_TICK =
            100_000

        private const val FRAME_CELL_SIZE =
            32.0
    }
}
