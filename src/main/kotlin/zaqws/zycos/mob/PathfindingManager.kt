@file:Suppress("unused")

package zaqws.zycos.mob

import com.destroystokyo.paper.entity.Pathfinder
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bukkit.ChunkSnapshot
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Mob
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.AreaManager
import zaqws.zycos.Constants
import zaqws.zycos.CoroutineManager
import zaqws.zycos.CoroutineManager.scope
import zaqws.zycos.distanceSquared2D
import zaqws.zycos.fastRemoveIf
import zaqws.zycos.later
import zaqws.zycos.toPosition
import zaqws.zycos.mob.MobPathfindingManager.MobPathfindingProfile
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds


internal class PathfindingManager {
    companion object {
        private const val STRAIGHT_MOVEMENT_COST = 1.0
        private const val DIAGONAL_MOVEMENT_COST = 1.4142135623730951
        private const val STEP_UP_COST = 0.5
        private const val COST_EPSILON = 1e-9
        private const val CANCELLATION_CHECK_MASK = 63
        private const val DEFAULT_MINIMUM_WORLD_HEIGHT = -64
        private const val DEFAULT_MAXIMUM_WORLD_HEIGHT = 320

        fun getChunkKey(chunkX: Int, chunkZ: Int): Long =
            (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

        private fun calculateMovementCost(
            source: AreaManager.Position,
            target: AreaManager.Position
        ): Double {
            val horizontalCost = if (source.x != target.x && source.z != target.z) {
                DIAGONAL_MOVEMENT_COST
            } else {
                STRAIGHT_MOVEMENT_COST
            }

            return horizontalCost + if (target.y > source.y) STEP_UP_COST else 0.0
        }

        private fun calculateHeuristic(
            source: AreaManager.Position,
            target: AreaManager.Position
        ): Double {
            val distanceX = abs(source.x - target.x)
            val distanceZ = abs(source.z - target.z)
            val diagonalSteps = min(distanceX, distanceZ)
            val straightSteps = max(distanceX, distanceZ) - diagonalSteps

            return diagonalSteps * DIAGONAL_MOVEMENT_COST + straightSteps * STRAIGHT_MOVEMENT_COST
        }

        private fun LongArray.calculatePathCost(): Double {
            var cost = 0.0

            for (index in 0 until size - 1) {
                cost += calculateMovementCost(
                    AreaManager.Position(this[index]),
                    AreaManager.Position(this[index + 1])
                )
            }

            return cost
        }
    }

    data class AbstractEdge(
        val targetEntranceId: Long,
        val cost: Double
    )

    class Entrance(
        val id: Long,
        val position: AreaManager.Position,
        val chunkX: Int,
        val chunkZ: Int
    ) {
        val interEdges = arrayListOf<AbstractEdge>()
        val intraEdges = arrayListOf<AbstractEdge>()
    }

    class Cluster(
        val chunkX: Int,
        val chunkZ: Int
    ) {
        val entranceIds = arrayListOf<Long>()
    }

    class HierarchicalGrid(
        val area: AreaManager.Area,
        val mobPathfindingProfile: MobPathfindingProfile = MobPathfindingProfile()
    ) {
        val hierarchicalLock = ReentrantReadWriteLock()
        internal val rebuildMutex = Mutex()
        @Volatile
        internal var active = true
        @Volatile
        internal var worldId: UUID? = null
        val clusters = hashMapOf<Long, Cluster>()
        val entrances = hashMapOf<Long, Entrance>()

        fun getOrCreateCluster(chunkX: Int, chunkZ: Int): Cluster =
            clusters.getOrPut(getChunkKey(chunkX, chunkZ)) {
                Cluster(chunkX, chunkZ)
            }

        fun getEntrance(entranceId: Long): Entrance? = entrances[entranceId]

        fun clearClusterData(chunkX: Int, chunkZ: Int) {
            val cluster = clusters[getChunkKey(chunkX, chunkZ)] ?: return
            val removedEntranceIds = cluster.entranceIds.toHashSet()

            for (entranceId in removedEntranceIds) {
                entrances.remove(entranceId)
            }
            cluster.entranceIds.clear()

            if (removedEntranceIds.isEmpty()) return

            for (neighborDeltaX in -1..1) {
                for (neighborDeltaZ in -1..1) {
                    if (neighborDeltaX == 0 && neighborDeltaZ == 0) continue

                    val neighborCluster = clusters[
                        getChunkKey(chunkX + neighborDeltaX, chunkZ + neighborDeltaZ)
                    ] ?: continue

                    for (entranceId in neighborCluster.entranceIds) {
                        val entrance = entrances[entranceId] ?: continue
                        entrance.interEdges.fastRemoveIf { it.targetEntranceId in removedEntranceIds }
                        entrance.intraEdges.fastRemoveIf { it.targetEntranceId in removedEntranceIds }
                    }
                }
            }

            pruneDisconnectedEntrancesAround(chunkX, chunkZ)
        }

        fun clear() {
            active = false
            hierarchicalLock.writeLock().lock()

            try {
                clearUnsafe()
            } finally {
                hierarchicalLock.writeLock().unlock()
            }
        }

        internal fun clearUnsafe() {
            clusters.clear()
            entrances.clear()
        }

        private fun pruneDisconnectedEntrancesAround(centerChunkX: Int, centerChunkZ: Int) {
            val entranceIdsToRemove = HashSet<Long>()

            for (neighborDeltaX in -1..1) {
                for (neighborDeltaZ in -1..1) {
                    if (neighborDeltaX == 0 && neighborDeltaZ == 0) continue

                    val neighborCluster = clusters[
                        getChunkKey(centerChunkX + neighborDeltaX, centerChunkZ + neighborDeltaZ)
                    ] ?: continue

                    for (entranceId in neighborCluster.entranceIds) {
                        if (!hasInterClusterConnection(entranceId)) {
                            entranceIdsToRemove.add(entranceId)
                        }
                    }
                }
            }

            if (entranceIdsToRemove.isEmpty()) return

            for (cluster in clusters.values) {
                cluster.entranceIds.fastRemoveIf { it in entranceIdsToRemove }
            }

            for (entranceId in entranceIdsToRemove) {
                entrances.remove(entranceId)
            }

            for (entrance in entrances.values) {
                entrance.intraEdges.fastRemoveIf { it.targetEntranceId in entranceIdsToRemove }
                entrance.interEdges.fastRemoveIf { it.targetEntranceId in entranceIdsToRemove }
            }
        }

        private fun hasInterClusterConnection(entranceId: Long): Boolean {
            val entrance = entrances[entranceId] ?: return false
            if (entrance.interEdges.isNotEmpty()) return true

            for (neighborDeltaX in -1..1) {
                for (neighborDeltaZ in -1..1) {
                    if (neighborDeltaX == 0 && neighborDeltaZ == 0) continue

                    val neighborCluster = clusters[
                        getChunkKey(entrance.chunkX + neighborDeltaX, entrance.chunkZ + neighborDeltaZ)
                    ] ?: continue

                    for (neighborEntranceId in neighborCluster.entranceIds) {
                        val neighborEntrance = entrances[neighborEntranceId] ?: continue
                        if (neighborEntrance.interEdges.any { it.targetEntranceId == entranceId }) return true
                    }
                }
            }

            return false
        }
    }

    class LocalPathfinder(
        var chunkSnapshots: Long2ObjectMap<ChunkSnapshot>,
        private val limitToCurrentChunk: Boolean = false,
        private val mobPathfindingProfile: MobPathfindingProfile = MobPathfindingProfile(),
        private val minimumWorldHeight: Int = DEFAULT_MINIMUM_WORLD_HEIGHT,
        private val maximumWorldHeight: Int = DEFAULT_MAXIMUM_WORLD_HEIGHT
    ) {
        private companion object {
            val DELTA_X_OFFSETS = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
            val DELTA_Z_OFFSETS = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        }

        private val openSet = PriorityQueue<PathNode>()
        private val accumulatedCostMap = Long2DoubleOpenHashMap().apply {
            defaultReturnValue(Double.MAX_VALUE)
        }
        private val navigationParentMap = Long2LongOpenHashMap().apply {
            defaultReturnValue(-1L)
        }
        private val pathListCache = LongArrayList()
        private val blockRadius = ceil((mobPathfindingProfile.mobWidth - 1.0) * 0.5).toInt().coerceAtLeast(0)

        private var lastChunkKey = Long.MIN_VALUE
        private var lastSnapshot: ChunkSnapshot? = null

        private class PathNode(
            val positionRaw: Long,
            val estimatedTotalCost: Double
        ) : Comparable<PathNode> {
            override fun compareTo(other: PathNode): Int =
                estimatedTotalCost.compareTo(other.estimatedTotalCost)
        }

        fun findPath(
            start: AreaManager.Position,
            end: AreaManager.Position,
            area: AreaManager.Area,
            maxNodes: Int = Int.MAX_VALUE,
            cancellationJob: Job? = null
        ): LongArray {
            if (start !in area || end !in area) return LongArray(0)

            resetSearchState()
            cancellationJob?.ensureActive()
            if (!isStandable(start) || !isStandable(end)) return LongArray(0)
            if (start.raw == end.raw) return longArrayOf(start.raw)

            accumulatedCostMap[start.raw] = 0.0
            openSet.add(PathNode(start.raw, calculateHeuristic(start, end)))

            var exploredNodes = 0

            while (openSet.isNotEmpty()) {
                exploredNodes++
                if (exploredNodes > maxNodes) return LongArray(0)
                checkCancellation(cancellationJob, exploredNodes)

                val currentRecord = openSet.poll()
                val currentPosition = AreaManager.Position(currentRecord.positionRaw)
                val currentAccumulatedCost = accumulatedCostMap.get(currentRecord.positionRaw)
                val expectedTotalCost = currentAccumulatedCost + calculateHeuristic(currentPosition, end)

                if (currentRecord.estimatedTotalCost > expectedTotalCost + COST_EPSILON) continue
                if (currentRecord.positionRaw == end.raw) {
                    return reconstructPath(navigationParentMap, currentRecord.positionRaw)
                }

                expandNeighbors(
                    currentPosition,
                    end,
                    area,
                    navigationParentMap
                )
            }

            return LongArray(0)
        }

        fun findCostsToAllEntrances(
            start: AreaManager.Position,
            entranceIds: List<Long>,
            area: AreaManager.Area,
            cancellationJob: Job? = null
        ): Long2DoubleOpenHashMap {
            val results = Long2DoubleOpenHashMap()
            if (start !in area || entranceIds.isEmpty()) return results

            val remainingTargets = HashSet<Long>(entranceIds.size)
            remainingTargets.addAll(entranceIds)
            resetSearchState()
            cancellationJob?.ensureActive()
            if (!isStandable(start)) return results

            accumulatedCostMap[start.raw] = 0.0
            openSet.add(PathNode(start.raw, 0.0))

            var exploredNodes = 0

            while (openSet.isNotEmpty() && remainingTargets.isNotEmpty()) {
                exploredNodes++
                checkCancellation(cancellationJob, exploredNodes)

                val currentRecord = openSet.poll()
                val currentAccumulatedCost = accumulatedCostMap.get(currentRecord.positionRaw)

                if (currentRecord.estimatedTotalCost > currentAccumulatedCost + COST_EPSILON) continue

                if (remainingTargets.remove(currentRecord.positionRaw)) {
                    results[currentRecord.positionRaw] = currentAccumulatedCost
                }

                expandNeighbors(
                    AreaManager.Position(currentRecord.positionRaw),
                    null,
                    area,
                    null
                )
            }

            return results
        }

        fun canTraverse(
            current: AreaManager.Position,
            target: AreaManager.Position,
            area: AreaManager.Area
        ): Boolean {
            if (current !in area || target !in area) return false

            val deltaX = target.x - current.x
            val deltaY = target.y - current.y
            val deltaZ = target.z - current.z

            if (deltaX !in -1..1 || deltaZ !in -1..1) return false
            if (deltaX == 0 && deltaZ == 0) return false
            if (deltaY > mobPathfindingProfile.maxStepUp || deltaY < -mobPathfindingProfile.maxStepDown) return false
            if (limitToCurrentChunk && (target.chunkX != current.chunkX || target.chunkZ != current.chunkZ)) return false
            if (!isWalkable(current, target)) return false
            if (deltaX != 0 && deltaZ != 0 && isDiagonalBlocked(current, deltaX, deltaZ, target.y)) return false

            return true
        }

        fun isStandable(position: AreaManager.Position): Boolean {
            if (!isReadableY(position.y - 1) || !isReadableY(position.y + mobPathfindingProfile.mobHeight - 1)) {
                return false
            }

            for (deltaZ in -blockRadius..blockRadius) {
                for (deltaX in -blockRadius..blockRadius) {
                    for (blockY in position.y until position.y + mobPathfindingProfile.mobHeight) {
                        if (getBlockMaterial(position.x + deltaX, blockY, position.z + deltaZ).isSolid) {
                            return false
                        }
                    }
                }
            }

            return getBlockMaterial(position.x, position.y - 1, position.z).isSolid
        }

        private fun resetSearchState() {
            openSet.clear()
            accumulatedCostMap.clear()
            navigationParentMap.clear()
            lastChunkKey = Long.MIN_VALUE
            lastSnapshot = null
        }

        private fun expandNeighbors(
            current: AreaManager.Position,
            end: AreaManager.Position?,
            area: AreaManager.Area,
            parentMap: Long2LongOpenHashMap?
        ) {
            val currentAccumulatedCost = accumulatedCostMap.get(current.raw)

            for (directionIndex in DELTA_X_OFFSETS.indices) {
                val deltaX = DELTA_X_OFFSETS[directionIndex]
                val deltaZ = DELTA_Z_OFFSETS[directionIndex]

                for (deltaY in mobPathfindingProfile.maxStepUp downTo -mobPathfindingProfile.maxStepDown) {
                    val target = AreaManager.Position(
                        current.x + deltaX,
                        current.y + deltaY,
                        current.z + deltaZ
                    )

                    if (!canTraverse(current, target, area)) continue

                    val tentativeAccumulatedCost = currentAccumulatedCost + calculateMovementCost(current, target)
                    if (tentativeAccumulatedCost >= accumulatedCostMap.get(target.raw)) continue

                    parentMap?.put(target.raw, current.raw)
                    accumulatedCostMap[target.raw] = tentativeAccumulatedCost

                    openSet.add(
                        PathNode(
                            target.raw,
                            tentativeAccumulatedCost + if (end == null) 0.0 else calculateHeuristic(target, end)
                        )
                    )
                }
            }
        }

        private fun reconstructPath(
            parentMap: Long2LongOpenHashMap,
            endRaw: Long
        ): LongArray {
            pathListCache.clear()

            var currentRaw = endRaw
            while (true) {
                pathListCache.add(currentRaw)
                if (!parentMap.containsKey(currentRaw)) break
                currentRaw = parentMap.get(currentRaw)
            }

            return LongArray(pathListCache.size) { index ->
                pathListCache.getLong(pathListCache.size - 1 - index)
            }
        }

        private fun isWalkable(
            current: AreaManager.Position,
            target: AreaManager.Position
        ): Boolean {
            val minimumBodyY = min(current.y, target.y)
            val maximumBodyY = max(current.y, target.y) + mobPathfindingProfile.mobHeight - 1

            if (!isReadableY(target.y - 1) || !isReadableY(maximumBodyY)) return false

            for (deltaZ in -blockRadius..blockRadius) {
                for (deltaX in -blockRadius..blockRadius) {
                    for (blockY in target.y..maximumBodyY) {
                        if (getBlockMaterial(target.x + deltaX, blockY, target.z + deltaZ).isSolid) {
                            return false
                        }
                    }
                }
            }

            if (!getBlockMaterial(target.x, target.y - 1, target.z).isSolid) return false

            if (target.y > current.y) {
                val minimumClearanceY = current.y + mobPathfindingProfile.mobHeight
                val maximumClearanceY = target.y + mobPathfindingProfile.mobHeight - 1
                if (!isReadableY(minimumClearanceY) || !isReadableY(maximumClearanceY)) return false

                for (deltaZ in -blockRadius..blockRadius) {
                    for (deltaX in -blockRadius..blockRadius) {
                        for (blockY in minimumClearanceY..maximumClearanceY) {
                            if (getBlockMaterial(current.x + deltaX, blockY, current.z + deltaZ).isSolid) {
                                return false
                            }
                        }
                    }
                }
            }

            if (minimumBodyY < minimumWorldHeight) return false
            return true
        }

        private fun isDiagonalBlocked(
            current: AreaManager.Position,
            deltaX: Int,
            deltaZ: Int,
            targetY: Int
        ): Boolean {
            val minimumHeight = min(current.y, targetY)
            val maximumHeight = max(current.y, targetY) + mobPathfindingProfile.mobHeight - 1

            if (!isReadableY(minimumHeight) || !isReadableY(maximumHeight)) return true

            for (offsetZ in -blockRadius..blockRadius) {
                for (offsetX in -blockRadius..blockRadius) {
                    for (blockY in minimumHeight..maximumHeight) {
                        if (getBlockMaterial(current.x + deltaX + offsetX, blockY, current.z + offsetZ).isSolid) return true
                        if (getBlockMaterial(current.x + offsetX, blockY, current.z + deltaZ + offsetZ).isSolid) return true
                    }
                }
            }

            return false
        }

        private fun getBlockMaterial(globalX: Int, globalY: Int, globalZ: Int): Material {
            if (!isReadableY(globalY)) return Material.BEDROCK

            val chunkKey = getChunkKey(
                globalX shr Constants.CHUNK_SHIFT,
                globalZ shr Constants.CHUNK_SHIFT
            )

            var snapshot = lastSnapshot
            if (chunkKey != lastChunkKey) {
                snapshot = chunkSnapshots.get(chunkKey)
                lastChunkKey = chunkKey
                lastSnapshot = snapshot
            }

            return snapshot?.getBlockType(globalX and 15, globalY, globalZ and 15) ?: Material.BEDROCK
        }

        private fun isReadableY(globalY: Int): Boolean =
            globalY in minimumWorldHeight..< maximumWorldHeight

        private fun checkCancellation(cancellationJob: Job?, exploredNodes: Int) {
            if (cancellationJob == null || exploredNodes and CANCELLATION_CHECK_MASK != 0) return
            if (!cancellationJob.isActive) throw CancellationException()
        }
    }

    class GridRegistry {
        private class AreaSnapshots(val worldId: UUID, expectedChunkCount: Int) :
            Long2ObjectOpenHashMap<ChunkSnapshot>(expectedChunkCount)

        private data class BorderTransition(
            val source: AreaManager.Position,
            val target: AreaManager.Position,
            val canTraverseForward: Boolean,
            val canTraverseReverse: Boolean
        )

        private val gridRegistryMap = ConcurrentHashMap<String, HierarchicalGrid>()

        fun getOrCreateGrid(
            identifier: String,
            area: AreaManager.Area,
            mobPathfindingProfile: MobPathfindingProfile = MobPathfindingProfile()
        ): HierarchicalGrid = gridRegistryMap.computeIfAbsent(identifier) {
            HierarchicalGrid(area, mobPathfindingProfile)
        }

        fun registerGrid(
            identifier: String,
            area: AreaManager.Area,
            mobPathfindingProfile: MobPathfindingProfile = MobPathfindingProfile()
        ): HierarchicalGrid = HierarchicalGrid(area, mobPathfindingProfile).also {
            gridRegistryMap.put(identifier, it)?.clear()
        }

        fun removeGrid(identifier: String): HierarchicalGrid? =
            gridRegistryMap.remove(identifier)?.also { it.clear() }

        fun clear() {
            gridRegistryMap.values.forEach { it.clear() }
            gridRegistryMap.clear()
        }

        fun captureAreaSnapshots(
            world: World,
            area: AreaManager.Area
        ): Long2ObjectMap<ChunkSnapshot> {
            val minimumChunkX = area.boundingBoxStart.chunkX - 1
            val maximumChunkX = area.boundingBoxEnd.chunkX + 1
            val minimumChunkZ = area.boundingBoxStart.chunkZ - 1
            val maximumChunkZ = area.boundingBoxEnd.chunkZ + 1
            val expectedChunkCount = (maximumChunkX - minimumChunkX + 1) * (maximumChunkZ - minimumChunkZ + 1)
            val snapshots = AreaSnapshots(world.uid, expectedChunkCount)

            for (chunkX in minimumChunkX..maximumChunkX) {
                for (chunkZ in minimumChunkZ..maximumChunkZ) {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) continue
                    snapshots[getChunkKey(chunkX, chunkZ)] = world.getChunkAt(chunkX, chunkZ).chunkSnapshot
                }
            }

            return snapshots
        }

        fun captureNeighborSnapshots(
            world: World,
            centerChunkX: Int,
            centerChunkZ: Int,
            radius: Int = 1
        ): Long2ObjectMap<ChunkSnapshot> {
            val snapshots = Long2ObjectOpenHashMap<ChunkSnapshot>(9)

            for (deltaX in -radius..radius) {
                for (deltaZ in -radius..radius) {
                    val targetChunkX = centerChunkX + deltaX
                    val targetChunkZ = centerChunkZ + deltaZ

                    if (!world.isChunkLoaded(targetChunkX, targetChunkZ)) continue
                    snapshots[getChunkKey(targetChunkX, targetChunkZ)] =
                        world.getChunkAt(targetChunkX, targetChunkZ).chunkSnapshot
                }
            }

            return snapshots
        }

        suspend fun bakeAll(
            hierarchicalGrid: HierarchicalGrid,
            chunkSnapshots: Long2ObjectMap<ChunkSnapshot>,
            minimumWorldHeight: Int = DEFAULT_MINIMUM_WORLD_HEIGHT,
            maximumWorldHeight: Int = DEFAULT_MAXIMUM_WORLD_HEIGHT
        ) = hierarchicalGrid.rebuildMutex.withLock {
            withContext(Dispatchers.Default) {
                hierarchicalGrid.hierarchicalLock.writeLock().lock()

                try {
                    ensureActive()
                    if (!hierarchicalGrid.active) return@withContext
                    hierarchicalGrid.worldId = (chunkSnapshots as? AreaSnapshots)?.worldId
                    hierarchicalGrid.clearUnsafe()

                    val area = hierarchicalGrid.area
                    for (chunkX in area.boundingBoxStart.chunkX..area.boundingBoxEnd.chunkX) {
                        for (chunkZ in area.boundingBoxStart.chunkZ..area.boundingBoxEnd.chunkZ) {
                            scanChunkBorders(
                                hierarchicalGrid,
                                chunkX,
                                chunkZ,
                                chunkSnapshots,
                                minimumWorldHeight,
                                maximumWorldHeight
                            )
                        }
                    }

                    val localPathfinder = LocalPathfinder(
                        chunkSnapshots,
                        limitToCurrentChunk = true,
                        mobPathfindingProfile = hierarchicalGrid.mobPathfindingProfile,
                        minimumWorldHeight = minimumWorldHeight,
                        maximumWorldHeight = maximumWorldHeight
                    )
                    for (cluster in hierarchicalGrid.clusters.values) {
                        bakeIntraEdges(
                            hierarchicalGrid,
                            cluster,
                            localPathfinder
                        )
                    }
                } finally {
                    hierarchicalGrid.hierarchicalLock.writeLock().unlock()
                }
            }
        }

        suspend fun rebuildChunk(
            hierarchicalGrid: HierarchicalGrid,
            chunkX: Int,
            chunkZ: Int,
            world: World,
            plugin: JavaPlugin
        ) {
            hierarchicalGrid.rebuildMutex.withLock {
                if (!hierarchicalGrid.active || hierarchicalGrid.worldId != world.uid) return
                val affectedRadius = max(1, ceil((hierarchicalGrid.mobPathfindingProfile.mobWidth - 1.0) / 32.0).toInt())
                val scanRadius = affectedRadius + 1
                val neighborSnapshots = withContext(CoroutineManager.PaperDispatcher(plugin)) {
                    captureNeighborSnapshots(world, chunkX, chunkZ, scanRadius + affectedRadius)
                }

                withContext(Dispatchers.Default) {
                    hierarchicalGrid.hierarchicalLock.writeLock().lock()

                    try {
                        ensureActive()
                        if (!hierarchicalGrid.active) return@withContext
                        for (deltaX in -affectedRadius..affectedRadius) {
                            for (deltaZ in -affectedRadius..affectedRadius) {
                                hierarchicalGrid.clearClusterData(chunkX + deltaX, chunkZ + deltaZ)
                            }
                        }

                        for (deltaX in -scanRadius..scanRadius) {
                            for (deltaZ in -scanRadius..scanRadius) {
                                scanChunkBorders(
                                    hierarchicalGrid,
                                    chunkX + deltaX,
                                    chunkZ + deltaZ,
                                    neighborSnapshots,
                                    world.minHeight,
                                    world.maxHeight
                                )
                            }
                        }

                        val localPathfinder = LocalPathfinder(
                            neighborSnapshots,
                            limitToCurrentChunk = true,
                            mobPathfindingProfile = hierarchicalGrid.mobPathfindingProfile,
                            minimumWorldHeight = world.minHeight,
                            maximumWorldHeight = world.maxHeight
                        )
                        for (deltaX in -scanRadius..scanRadius) {
                            for (deltaZ in -scanRadius..scanRadius) {
                                val cluster = hierarchicalGrid.clusters[
                                    getChunkKey(chunkX + deltaX, chunkZ + deltaZ)
                                ] ?: continue

                                bakeIntraEdges(hierarchicalGrid, cluster, localPathfinder)
                            }
                        }
                    } finally {
                        hierarchicalGrid.hierarchicalLock.writeLock().unlock()
                    }
                }
            }
        }

        private fun scanChunkBorders(
            grid: HierarchicalGrid,
            chunkX: Int,
            chunkZ: Int,
            snapshots: Long2ObjectMap<ChunkSnapshot>,
            minimumWorldHeight: Int,
            maximumWorldHeight: Int
        ) {
            if (chunkX !in grid.area.boundingBoxStart.chunkX..grid.area.boundingBoxEnd.chunkX ||
                chunkZ !in grid.area.boundingBoxStart.chunkZ..grid.area.boundingBoxEnd.chunkZ
            ) return
            if (!snapshots.containsKey(getChunkKey(chunkX, chunkZ))) return

            val currentCluster = grid.getOrCreateCluster(chunkX, chunkZ)
            val borderStartX = chunkX shl Constants.CHUNK_SHIFT
            val borderStartZ = chunkZ shl Constants.CHUNK_SHIFT
            val transitionPathfinder = LocalPathfinder(
                snapshots,
                limitToCurrentChunk = false,
                mobPathfindingProfile = grid.mobPathfindingProfile,
                minimumWorldHeight = minimumWorldHeight,
                maximumWorldHeight = maximumWorldHeight
            )

            scanSingleAxis(
                grid,
                currentCluster,
                transitionPathfinder,
                true,
                borderStartX,
                borderStartZ
            )
            scanSingleAxis(
                grid,
                currentCluster,
                transitionPathfinder,
                false,
                borderStartX,
                borderStartZ
            )
        }

        private fun scanSingleAxis(
            grid: HierarchicalGrid,
            currentCluster: Cluster,
            transitionPathfinder: LocalPathfinder,
            isEastAxis: Boolean,
            borderStartX: Int,
            borderStartZ: Int
        ) {
            val profile = grid.mobPathfindingProfile

            for (sourceY in grid.area.boundingBoxStart.y..grid.area.boundingBoxEnd.y) {
                for (deltaY in max(profile.maxStepUp, profile.maxStepDown) downTo -max(profile.maxStepUp, profile.maxStepDown)) {
                    for (transverseDelta in -1..1) {
                        var runStartOffset = -1
                        var runTransition: BorderTransition? = null

                        for (offset in 0..16) {
                            val transition = if (offset < 16) {
                                createBorderTransition(
                                    transitionPathfinder,
                                    grid.area,
                                    borderStartX,
                                    borderStartZ,
                                    sourceY,
                                    deltaY,
                                    transverseDelta,
                                    offset,
                                    isEastAxis
                                )
                            } else {
                                null
                            }

                            if (transition != null &&
                                (runTransition == null || isSameTransitionRun(runTransition, transition))
                            ) {
                                if (runStartOffset == -1) {
                                    runStartOffset = offset
                                    runTransition = transition
                                }
                                continue
                            }

                            if (runStartOffset != -1) {
                                val runEndOffset = offset - 1
                                val middleOffset = runStartOffset + (runEndOffset - runStartOffset) / 2
                                val middleTransition = createBorderTransition(
                                    transitionPathfinder,
                                    grid.area,
                                    borderStartX,
                                    borderStartZ,
                                    sourceY,
                                    deltaY,
                                    transverseDelta,
                                    middleOffset,
                                    isEastAxis
                                )

                                if (middleTransition != null) {
                                    registerEntrancePair(
                                        grid,
                                        currentCluster,
                                        middleTransition
                                    )
                                }
                            }

                            if (transition != null) {
                                runStartOffset = offset
                                runTransition = transition
                            } else {
                                runStartOffset = -1
                                runTransition = null
                            }
                        }
                    }
                }
            }
        }

        private fun createBorderTransition(
            transitionPathfinder: LocalPathfinder,
            area: AreaManager.Area,
            borderStartX: Int,
            borderStartZ: Int,
            sourceY: Int,
            deltaY: Int,
            transverseDelta: Int,
            offset: Int,
            isEastAxis: Boolean
        ): BorderTransition? {
            val source = if (isEastAxis) {
                AreaManager.Position(borderStartX + 15, sourceY, borderStartZ + offset)
            } else {
                AreaManager.Position(borderStartX + offset, sourceY, borderStartZ + 15)
            }

            val target = if (isEastAxis) {
                AreaManager.Position(borderStartX + 16, sourceY + deltaY, borderStartZ + offset + transverseDelta)
            } else {
                AreaManager.Position(borderStartX + offset + transverseDelta, sourceY + deltaY, borderStartZ + 16)
            }

            if (source !in area || target !in area) return null
            if (!transitionPathfinder.chunkSnapshots.containsKey(getChunkKey(target.chunkX, target.chunkZ))) return null
            if (!transitionPathfinder.isStandable(source) || !transitionPathfinder.isStandable(target)) return null

            val canTraverseForward = transitionPathfinder.canTraverse(source, target, area)
            val canTraverseReverse = transitionPathfinder.canTraverse(target, source, area)
            if (!canTraverseForward && !canTraverseReverse) return null

            return BorderTransition(
                source,
                target,
                canTraverseForward,
                canTraverseReverse
            )
        }

        private fun isSameTransitionRun(
            first: BorderTransition,
            second: BorderTransition
        ): Boolean =
            first.canTraverseForward == second.canTraverseForward &&
                    first.canTraverseReverse == second.canTraverseReverse &&
                    first.target.chunkX == second.target.chunkX &&
                    first.target.chunkZ == second.target.chunkZ

        private fun registerEntrancePair(
            grid: HierarchicalGrid,
            sourceCluster: Cluster,
            transition: BorderTransition
        ) {
            val sourcePosition = transition.source
            val targetPosition = transition.target
            val targetCluster = grid.getOrCreateCluster(targetPosition.chunkX, targetPosition.chunkZ)
            val sourceEntrance = grid.entrances.getOrPut(sourcePosition.raw) {
                Entrance(
                    sourcePosition.raw,
                    sourcePosition,
                    sourceCluster.chunkX,
                    sourceCluster.chunkZ
                ).also { sourceCluster.entranceIds.add(it.id) }
            }
            val targetEntrance = grid.entrances.getOrPut(targetPosition.raw) {
                Entrance(
                    targetPosition.raw,
                    targetPosition,
                    targetCluster.chunkX,
                    targetCluster.chunkZ
                ).also { targetCluster.entranceIds.add(it.id) }
            }

            if (transition.canTraverseForward) {
                addInterEdgeIfAbsent(
                    sourceEntrance,
                    targetEntrance.id,
                    calculateMovementCost(sourcePosition, targetPosition)
                )
            }

            if (transition.canTraverseReverse) {
                addInterEdgeIfAbsent(
                    targetEntrance,
                    sourceEntrance.id,
                    calculateMovementCost(targetPosition, sourcePosition)
                )
            }
        }

        private fun addInterEdgeIfAbsent(
            sourceEntrance: Entrance,
            targetEntranceId: Long,
            cost: Double
        ) {
            if (sourceEntrance.interEdges.none { it.targetEntranceId == targetEntranceId }) {
                sourceEntrance.interEdges.add(AbstractEdge(targetEntranceId, cost))
            }
        }

        private fun bakeIntraEdges(
            grid: HierarchicalGrid,
            cluster: Cluster,
            pathfinder: LocalPathfinder
        ) {
            val entranceIds = cluster.entranceIds
            if (entranceIds.isEmpty()) return

            for (entranceId in entranceIds) {
                grid.entrances[entranceId]?.intraEdges?.clear()
            }

            if (entranceIds.size < 2) return

            for (sourceEntranceId in entranceIds) {
                val sourceEntrance = grid.getEntrance(sourceEntranceId) ?: continue
                val costs = pathfinder.findCostsToAllEntrances(
                    sourceEntrance.position,
                    entranceIds,
                    grid.area
                )

                for (targetEntranceId in entranceIds) {
                    if (targetEntranceId == sourceEntranceId || !costs.containsKey(targetEntranceId)) continue
                    sourceEntrance.intraEdges.add(
                        AbstractEdge(targetEntranceId, costs.get(targetEntranceId))
                    )
                }
            }
        }
    }

    class HierarchicalPathfinder {
        private class MacroPathNode(
            val positionRaw: Long,
            val estimatedTotalCost: Double
        ) : Comparable<MacroPathNode> {
            override fun compareTo(other: MacroPathNode): Int =
                estimatedTotalCost.compareTo(other.estimatedTotalCost)
        }

        suspend fun findHierarchicalPath(
            source: AreaManager.Position,
            target: AreaManager.Position,
            grid: HierarchicalGrid,
            chunkSnapshots: Long2ObjectMap<ChunkSnapshot>,
            minimumWorldHeight: Int = DEFAULT_MINIMUM_WORLD_HEIGHT,
            maximumWorldHeight: Int = DEFAULT_MAXIMUM_WORLD_HEIGHT
        ): LongArray = withContext(Dispatchers.Default) {
            if (!grid.active) return@withContext LongArray(0)
            if (source !in grid.area || target !in grid.area) return@withContext LongArray(0)

            val cancellationJob = coroutineContext[Job]
            val localPathfinder = LocalPathfinder(
                chunkSnapshots,
                limitToCurrentChunk = true,
                mobPathfindingProfile = grid.mobPathfindingProfile,
                minimumWorldHeight = minimumWorldHeight,
                maximumWorldHeight = maximumWorldHeight
            )
            if (!localPathfinder.isStandable(source) || !localPathfinder.isStandable(target)) {
                return@withContext LongArray(0)
            }
            if (source.raw == target.raw) return@withContext longArrayOf(source.raw)

            if (source.chunkX == target.chunkX && source.chunkZ == target.chunkZ) {
                val directPath = localPathfinder.findPath(
                    source,
                    target,
                    grid.area,
                    cancellationJob = cancellationJob
                )

                if (directPath.isNotEmpty()) {
                    return@withContext longArrayOf(source.raw, target.raw)
                }
            }

            grid.hierarchicalLock.readLock().lock()

            try {
                val openSet = PriorityQueue<MacroPathNode>()
                val accumulatedCostMap = Long2DoubleOpenHashMap().apply {
                    defaultReturnValue(Double.MAX_VALUE)
                }
                val navigationParentMap = Long2LongOpenHashMap().apply {
                    defaultReturnValue(-1L)
                }
                val sourceCluster = grid.clusters[getChunkKey(source.chunkX, source.chunkZ)]

                accumulatedCostMap[source.raw] = 0.0
                openSet.add(MacroPathNode(source.raw, calculateHeuristic(source, target)))

                while (openSet.isNotEmpty()) {
                    ensureActive()

                    val currentRecord = openSet.poll()
                    val currentPositionRaw = currentRecord.positionRaw
                    val currentAccumulatedCost = accumulatedCostMap.get(currentPositionRaw)
                    val currentEntrance = grid.entrances[currentPositionRaw]
                    val currentPosition = when (currentPositionRaw) {
                        source.raw -> source
                        target.raw -> target
                        else -> currentEntrance?.position ?: continue
                    }
                    val expectedTotalCost = currentAccumulatedCost + calculateHeuristic(currentPosition, target)

                    if (currentRecord.estimatedTotalCost > expectedTotalCost + COST_EPSILON) continue
                    if (currentPositionRaw == target.raw) {
                        return@withContext reconstructMacroPath(navigationParentMap, target.raw)
                    }

                    if (currentPositionRaw == source.raw) {
                        if (sourceCluster != null) {
                            expandSourceCluster(
                                source,
                                target,
                                sourceCluster,
                                grid,
                                localPathfinder,
                                cancellationJob,
                                accumulatedCostMap,
                                navigationParentMap,
                                openSet
                            )
                        }
                    }

                    if (currentEntrance != null &&
                        currentEntrance.chunkX == target.chunkX &&
                        currentEntrance.chunkZ == target.chunkZ
                    ) {
                        connectTarget(
                            currentEntrance,
                            currentPositionRaw,
                            currentAccumulatedCost,
                            target,
                            grid,
                            localPathfinder,
                            cancellationJob,
                            accumulatedCostMap,
                            navigationParentMap,
                            openSet
                        )
                    }

                    if (currentEntrance != null) {
                        expandEntranceEdges(
                            currentEntrance.intraEdges,
                            currentPositionRaw,
                            currentAccumulatedCost,
                            target,
                            grid,
                            accumulatedCostMap,
                            navigationParentMap,
                            openSet
                        )
                        expandEntranceEdges(
                            currentEntrance.interEdges,
                            currentPositionRaw,
                            currentAccumulatedCost,
                            target,
                            grid,
                            accumulatedCostMap,
                            navigationParentMap,
                            openSet
                        )
                    }
                }

                LongArray(0)
            } finally {
                grid.hierarchicalLock.readLock().unlock()
            }
        }

        private fun expandSourceCluster(
            source: AreaManager.Position,
            target: AreaManager.Position,
            sourceCluster: Cluster,
            grid: HierarchicalGrid,
            localPathfinder: LocalPathfinder,
            cancellationJob: Job?,
            accumulatedCostMap: Long2DoubleOpenHashMap,
            navigationParentMap: Long2LongOpenHashMap,
            openSet: PriorityQueue<MacroPathNode>
        ) {
            val entranceIds = sourceCluster.entranceIds
            val costs = localPathfinder.findCostsToAllEntrances(
                source,
                entranceIds,
                grid.area,
                cancellationJob
            )

            for (entranceId in entranceIds) {
                if (!costs.containsKey(entranceId)) continue

                val entrance = grid.entrances[entranceId] ?: continue
                val pathCost = costs.get(entranceId)

                if (pathCost >= accumulatedCostMap.get(entranceId)) continue

                accumulatedCostMap[entranceId] = pathCost
                navigationParentMap[entranceId] = source.raw
                openSet.add(
                    MacroPathNode(
                        entranceId,
                        pathCost + calculateHeuristic(entrance.position, target)
                    )
                )
            }
        }

        private fun connectTarget(
            currentEntrance: Entrance,
            currentPositionRaw: Long,
            currentAccumulatedCost: Double,
            target: AreaManager.Position,
            grid: HierarchicalGrid,
            localPathfinder: LocalPathfinder,
            cancellationJob: Job?,
            accumulatedCostMap: Long2DoubleOpenHashMap,
            navigationParentMap: Long2LongOpenHashMap,
            openSet: PriorityQueue<MacroPathNode>
        ) {
            val localPath = localPathfinder.findPath(
                currentEntrance.position,
                target,
                grid.area,
                cancellationJob = cancellationJob
            )
            if (localPath.isEmpty()) return

            val tentativeAccumulatedCost = currentAccumulatedCost + localPath.calculatePathCost()
            if (tentativeAccumulatedCost >= accumulatedCostMap.get(target.raw)) return

            accumulatedCostMap[target.raw] = tentativeAccumulatedCost
            navigationParentMap[target.raw] = currentPositionRaw
            openSet.add(MacroPathNode(target.raw, tentativeAccumulatedCost))
        }

        private fun expandEntranceEdges(
            edges: ArrayList<AbstractEdge>,
            currentPositionRaw: Long,
            currentAccumulatedCost: Double,
            target: AreaManager.Position,
            grid: HierarchicalGrid,
            accumulatedCostMap: Long2DoubleOpenHashMap,
            navigationParentMap: Long2LongOpenHashMap,
            openSet: PriorityQueue<MacroPathNode>
        ) {
            for ((targetEntranceId, cost) in edges) {
                val neighborEntrance = grid.entrances[targetEntranceId] ?: continue
                val tentativeAccumulatedCost = currentAccumulatedCost + cost

                if (tentativeAccumulatedCost >= accumulatedCostMap.get(targetEntranceId)) continue

                accumulatedCostMap[targetEntranceId] = tentativeAccumulatedCost
                navigationParentMap[targetEntranceId] = currentPositionRaw
                openSet.add(
                    MacroPathNode(
                        targetEntranceId,
                        tentativeAccumulatedCost + calculateHeuristic(neighborEntrance.position, target)
                    )
                )
            }
        }

        private fun reconstructMacroPath(
            navigationParentMap: Long2LongOpenHashMap,
            targetPositionRaw: Long
        ): LongArray {
            val pathList = LongArrayList()
            var currentRaw = targetPositionRaw

            while (true) {
                pathList.add(currentRaw)
                if (!navigationParentMap.containsKey(currentRaw)) break
                currentRaw = navigationParentMap.get(currentRaw)
            }

            return LongArray(pathList.size) { index ->
                pathList.getLong(pathList.size - 1 - index)
            }
        }
    }

    class HierarchicalNavigator(
        private val plugin: JavaPlugin,
        private val entity: Mob,
        private val hierarchicalGrid: HierarchicalGrid,
        private val scope: CoroutineScope,
        private val gridRegistry: GridRegistry
    ) {
        private companion object {
            const val TARGET_REUSE_DISTANCE = 1.5
            const val TARGET_REUSE_DISTANCE_SQUARED = TARGET_REUSE_DISTANCE * TARGET_REUSE_DISTANCE
            const val WAYPOINT_REACHED_DISTANCE = 0.8
            const val WAYPOINT_REACHED_DISTANCE_SQUARED = WAYPOINT_REACHED_DISTANCE * WAYPOINT_REACHED_DISTANCE
            const val MOVE_TARGET_LOOKAHEAD_NODES = 4
            const val MOVE_START_MAXIMUM_FAILURES = 5
            const val MOVE_RETRY_DELAY_MILLISECONDS = 100L
            const val FAILURE_RETRY_DELAY_MILLISECONDS = 1000L
            const val NAVIGATION_TICK_MILLISECONDS = 50L
            const val STUCK_TIMEOUT_NANOSECONDS = 10_000_000_000L
            const val MINIMUM_PROGRESS_DISTANCE_SQUARED = 0.01
        }

        private val hierarchicalPathfinder = HierarchicalPathfinder()
        private val mobHeight = ceil(entity.height).toInt()
        private val mobWidth = entity.width
        private val worldId = entity.world.uid

        private var macroPath: LongArray? = null
        private var localPath: LongArray? = null
        private var macroIndex = 0
        private var localIndex = 0
        private var activeMoveTargetIndex = -1
        private var searchJob: Job? = null
        private var localSearchJob: Job? = null
        private var navigationJob: Job? = null
        private var lastTargetLocation: Location? = null
        private var lastFailureTime = 0L
        private var nextMoveAttemptTime = 0L
        private var moveStartFailureCount = 0
        private var requestGeneration = 0L
        private var lastProgressLocation = entity.location
        private var lastProgressTime = System.nanoTime()

        var speed: Double = 1.0
            set(value) {
                require(value.isFinite() && value > 0.0)
                if (field == value) return
                field = value
                triggerMove()
            }

        var failed = false
            private set

        init {
            require(mobHeight <= hierarchicalGrid.mobPathfindingProfile.mobHeight) {
                "The hierarchical grid navigation profile is shorter than the mob."
            }
            require(mobWidth <= hierarchicalGrid.mobPathfindingProfile.mobWidth + COST_EPSILON) {
                "The hierarchical grid navigation profile is narrower than the mob."
            }
        }

        fun navigateTo(targetLocation: Location) {
            if (!entity.isValid || entity.isDead || !hierarchicalGrid.active || entity.world.uid != worldId) {
                stopNavigation()
                return
            }

            if (targetLocation.world != entity.world ||
                !targetLocation.x.isFinite() || !targetLocation.y.isFinite() || !targetLocation.z.isFinite() ||
                targetLocation.toPosition() !in hierarchicalGrid.area
            ) {
                stopNavigation()
                return
            }

            val previousTarget = lastTargetLocation
            val targetChanged = previousTarget == null ||
                    previousTarget.world != targetLocation.world ||
                    previousTarget.distanceSquared(targetLocation) > TARGET_REUSE_DISTANCE_SQUARED

            if (targetChanged) {
                requestPathAsync(
                    entity.location.toPosition(),
                    targetLocation.toPosition(),
                    targetLocation
                )
                return
            }

            if (searchJob?.isActive == true) return

            val activeMacroPath = macroPath
            if (activeMacroPath == null || macroIndex >= activeMacroPath.size) {
                if (!failed && entity.location.distanceSquared(targetLocation) <= TARGET_REUSE_DISTANCE_SQUARED) {
                    stopNavigation()
                    return
                }
                if (canRetryPathfinding()) {
                    requestPathAsync(
                        entity.location.toPosition(),
                        targetLocation.toPosition(),
                        targetLocation
                    )
                }
                return
            }

            advanceLocalPathIfReached(targetLocation)
            if (macroPath !== activeMacroPath) return

            if (localPath != null && localIndex < localPath!!.size) {
                val nextNode = AreaManager.Position(localPath!![localIndex])
                if (!entity.world.isChunkLoaded(nextNode.chunkX, nextNode.chunkZ)) {
                    registerFailure()
                    return
                }
                val location = entity.location
                if (location.distanceSquared(lastProgressLocation) >= MINIMUM_PROGRESS_DISTANCE_SQUARED) {
                    lastProgressLocation = location
                    lastProgressTime = System.nanoTime()
                } else if (System.nanoTime() - lastProgressTime >= STUCK_TIMEOUT_NANOSECONDS) {
                    registerFailure()
                    return
                }
                if (!entity.pathfinder.hasPath()) triggerMove()
                return
            }

            if (macroIndex >= activeMacroPath.size - 1) {
                stopNavigation()
                return
            }

            if (localSearchJob?.isActive == true) return
            requestLocalPath(activeMacroPath)
        }

        fun cancel() {
            stopNavigation()
        }

        private fun requestLocalPath(
            activeMacroPath: LongArray
        ) {
            val currentChunkX = entity.location.blockX shr Constants.CHUNK_SHIFT
            val currentChunkZ = entity.location.blockZ shr Constants.CHUNK_SHIFT
            val nextMacroNode = AreaManager.Position(activeMacroPath[macroIndex + 1])
            val snapshots = obtainLocalSnapshots(
                currentChunkX,
                currentChunkZ,
                nextMacroNode.chunkX,
                nextMacroNode.chunkZ
            )
            val pathfinder = LocalPathfinder(
                snapshots,
                limitToCurrentChunk = false,
                mobPathfindingProfile = MobPathfindingProfile(
                    mobHeight = mobHeight,
                    mobWidth = mobWidth,
                    maxStepUp = hierarchicalGrid.mobPathfindingProfile.maxStepUp,
                    maxStepDown = hierarchicalGrid.mobPathfindingProfile.maxStepDown
                ),
                minimumWorldHeight = entity.world.minHeight,
                maximumWorldHeight = entity.world.maxHeight
            )

            val sourcePosition = entity.location.toPosition()
            val generation = requestGeneration

            localSearchJob?.cancel()
            localSearchJob = scope.launch(CoroutineManager.PaperDispatcher(plugin)) {
                val generatedPath = withContext(Dispatchers.Default) {
                    pathfinder.findPath(
                        sourcePosition,
                        nextMacroNode,
                        hierarchicalGrid.area,
                        cancellationJob = coroutineContext[Job]
                    )
                }

                withContext(CoroutineManager.PaperDispatcher(plugin)) {
                    if (generation != requestGeneration) return@withContext
                    if (!entity.isValid || entity.isDead || entity.world.uid != worldId) return@withContext
                    localSearchJob = null

                    if (generatedPath.isEmpty()) {
                        registerFailure()
                        return@withContext
                    }

                    failed = false

                    if (generatedPath.size == 1) {
                        macroIndex++
                        localPath = null
                        localIndex = 0
                        activeMoveTargetIndex = -1
                        navigateTo(lastTargetLocation ?: return@withContext)
                        return@withContext
                    }

                    localPath = generatedPath
                    localIndex = 1
                    activeMoveTargetIndex = -1
                    lastProgressLocation = entity.location
                    lastProgressTime = System.nanoTime()
                    triggerMove()
                }
            }
        }

        private fun advanceLocalPathIfReached(targetLocation: Location) {
            val activeLocalPath = localPath ?: return
            if (localIndex >= activeLocalPath.size) return

            val targetIndex = activeMoveTargetIndex.takeIf { it in localIndex until activeLocalPath.size } ?: localIndex
            val targetNodePosition = AreaManager.Position(activeLocalPath[targetIndex])
            val targetNodeCenter = targetNodePosition
                .toLocation(entity.world)
                .add(0.5, 0.0, 0.5)

            if (entity.location.distanceSquared2D(targetNodeCenter) >= WAYPOINT_REACHED_DISTANCE_SQUARED ||
                entity.location.blockY != targetNodePosition.y
            ) {
                return
            }

            localIndex = targetIndex + 1
            activeMoveTargetIndex = -1
            nextMoveAttemptTime = 0L
            moveStartFailureCount = 0

            if (localIndex < activeLocalPath.size) {
                triggerMove()
                return
            }

            localPath = null
            localIndex = 0
            macroIndex++
            navigateTo(targetLocation)
        }

        private fun obtainLocalSnapshots(
            currentChunkX: Int,
            currentChunkZ: Int,
            nextChunkX: Int,
            nextChunkZ: Int
        ): Long2ObjectMap<ChunkSnapshot> {
            return Long2ObjectOpenHashMap<ChunkSnapshot>(18).also { snapshots ->
                snapshots.putAll(
                    gridRegistry.captureNeighborSnapshots(entity.world, currentChunkX, currentChunkZ)
                )
                snapshots.putAll(
                    gridRegistry.captureNeighborSnapshots(entity.world, nextChunkX, nextChunkZ)
                )
            }
        }

        private fun triggerMove() {
            val activeLocalPath = localPath ?: return
            if (localIndex >= activeLocalPath.size) return

            val currentTime = System.nanoTime()
            if (currentTime < nextMoveAttemptTime) return

            val preferredTargetIndex = min(localIndex + MOVE_TARGET_LOOKAHEAD_NODES, activeLocalPath.lastIndex)
            var selectedPath: Pathfinder.PathResult? = null
            var selectedTargetIndex = -1

            for (candidateIndex in preferredTargetIndex downTo localIndex) {
                val candidatePosition = AreaManager.Position(activeLocalPath[candidateIndex])
                if (!entity.world.isChunkLoaded(candidatePosition.chunkX, candidatePosition.chunkZ)) continue

                val candidateLocation = candidatePosition
                    .toLocation(entity.world)
                    .add(0.5, 0.0, 0.5)
                val candidatePath = entity.pathfinder.findPath(candidateLocation) ?: continue
                if (!candidatePath.canReachFinalPoint()) continue
                if (candidatePath.points.any { it.toPosition() !in hierarchicalGrid.area }) continue
                val finalPoint = candidatePath.finalPoint ?: continue

                if (finalPoint.blockX != candidatePosition.x ||
                    finalPoint.blockY != candidatePosition.y ||
                    finalPoint.blockZ != candidatePosition.z
                ) continue

                selectedPath = candidatePath
                selectedTargetIndex = candidateIndex
                break
            }

            if (selectedPath != null && entity.pathfinder.moveTo(selectedPath, speed)) {
                activeMoveTargetIndex = selectedTargetIndex
                nextMoveAttemptTime = 0L
                moveStartFailureCount = 0
                return
            }

            activeMoveTargetIndex = -1
            moveStartFailureCount++
            if (moveStartFailureCount >= MOVE_START_MAXIMUM_FAILURES) {
                registerFailure()
                return
            }

            nextMoveAttemptTime = currentTime + MOVE_RETRY_DELAY_MILLISECONDS * 1_000_000L
        }

        private fun requestPathAsync(
            sourcePosition: AreaManager.Position,
            targetPosition: AreaManager.Position,
            targetLocation: Location
        ) {
            requestGeneration++
            val generation = requestGeneration

            searchJob?.cancel()
            localSearchJob?.cancel()
            searchJob = null
            localSearchJob = null

            lastTargetLocation = targetLocation.clone()
            entity.pathfinder.stopPathfinding()
            macroPath = null
            localPath = null
            macroIndex = 0
            localIndex = 0
            activeMoveTargetIndex = -1
            nextMoveAttemptTime = 0L
            moveStartFailureCount = 0

            val snapshots = Long2ObjectOpenHashMap<ChunkSnapshot>(18)
            snapshots.putAll(
                gridRegistry.captureNeighborSnapshots(
                    entity.world,
                    sourcePosition.chunkX,
                    sourcePosition.chunkZ
                )
            )
            snapshots.putAll(
                gridRegistry.captureNeighborSnapshots(
                    entity.world,
                    targetPosition.chunkX,
                    targetPosition.chunkZ
                )
            )
            if (navigationJob?.isActive != true) {
                navigationJob = scope.launch(CoroutineManager.PaperDispatcher(plugin)) {
                    while (true) {
                        delay(NAVIGATION_TICK_MILLISECONDS.milliseconds)
                        navigateTo(lastTargetLocation ?: break)
                    }
                }
            }

            searchJob = scope.launch(CoroutineManager.PaperDispatcher(plugin)) {
                val resultPath = hierarchicalPathfinder.findHierarchicalPath(
                    sourcePosition,
                    targetPosition,
                    hierarchicalGrid,
                    snapshots,
                    entity.world.minHeight,
                    entity.world.maxHeight
                )

                withContext(CoroutineManager.PaperDispatcher(plugin)) {
                    if (generation != requestGeneration) return@withContext
                    if (!entity.isValid || entity.isDead || entity.world.uid != worldId) return@withContext
                    searchJob = null

                    if (resultPath.isEmpty()) {
                        registerFailure()
                        return@withContext
                    }

                    failed = false
                    macroPath = resultPath
                    macroIndex = 0
                    localPath = null
                    localIndex = 0
                    activeMoveTargetIndex = -1
                    navigateTo(lastTargetLocation ?: return@withContext)
                }
            }
        }

        private fun registerFailure() {
            failed = true
            lastFailureTime = System.nanoTime()
            macroPath = null
            localPath = null
            activeMoveTargetIndex = -1
            nextMoveAttemptTime = 0L
            moveStartFailureCount = 0
            entity.pathfinder.stopPathfinding()
        }

        private fun canRetryPathfinding(): Boolean =
            !failed || System.nanoTime() - lastFailureTime >= FAILURE_RETRY_DELAY_MILLISECONDS * 1_000_000L

        private fun stopNavigation() {
            requestGeneration++
            searchJob?.cancel()
            localSearchJob?.cancel()
            navigationJob?.cancel()
            searchJob = null
            localSearchJob = null
            navigationJob = null
            macroPath = null
            localPath = null
            macroIndex = 0
            localIndex = 0
            activeMoveTargetIndex = -1
            lastTargetLocation = null
            failed = false
            nextMoveAttemptTime = 0L
            moveStartFailureCount = 0
            entity.pathfinder.stopPathfinding()
        }
    }

    class PathfindingUpdateListener(
        private val plugin: JavaPlugin,
        private val gridRegistry: GridRegistry,
        private val hierarchicalGrid: HierarchicalGrid
    ) : Listener {
        private val rebuildJobs = ConcurrentHashMap<Long, Job>()
        private var registered = true

        fun unregister() {
            registered = false
            rebuildJobs.values.forEach { it.cancel() }
            rebuildJobs.clear()
            HandlerList.unregisterAll(this)
        }

        private fun updateGridAt(location: Location) {
            if (!hierarchicalGrid.active || hierarchicalGrid.worldId != location.world.uid) return
            val position = location.toPosition()
            val area = hierarchicalGrid.area
            val profile = hierarchicalGrid.mobPathfindingProfile
            val radius = ceil((profile.mobWidth - 1.0) * 0.5).toInt().coerceAtLeast(0)
            if (position.x !in area.boundingBoxStart.x - radius..area.boundingBoxEnd.x + radius ||
                position.z !in area.boundingBoxStart.z - radius..area.boundingBoxEnd.z + radius ||
                position.y !in area.boundingBoxStart.y - 1..< area.boundingBoxEnd.y + profile.mobHeight
            ) return

            later {
                requestChunkRebuild(
                    position.chunkX,
                    position.chunkZ,
                    location.world
                )
            }
        }

        private fun requestChunkRebuild(
            chunkX: Int,
            chunkZ: Int,
            world: World
        ) {
            if (!registered || !hierarchicalGrid.active || hierarchicalGrid.worldId != world.uid) return
            val chunkKey = getChunkKey(chunkX, chunkZ)
            rebuildJobs.remove(chunkKey)?.cancel()

            val job = plugin.scope.launch {
                gridRegistry.rebuildChunk(
                    hierarchicalGrid,
                    chunkX,
                    chunkZ,
                    world,
                    plugin
                )
            }

            rebuildJobs[chunkKey] = job
            job.invokeOnCompletion {
                rebuildJobs.remove(chunkKey, job)
            }
        }

        private fun isChunkInsideArea(chunkX: Int, chunkZ: Int): Boolean =
            chunkX in hierarchicalGrid.area.boundingBoxStart.chunkX - 1..hierarchicalGrid.area.boundingBoxEnd.chunkX + 1 &&
                    chunkZ in hierarchicalGrid.area.boundingBoxStart.chunkZ - 1..hierarchicalGrid.area.boundingBoxEnd.chunkZ + 1

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onBreak(event: BlockBreakEvent) {
            updateGridAt(event.block.location)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onPlace(event: BlockPlaceEvent) {
            updateGridAt(event.block.location)
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onChunkLoad(event: ChunkLoadEvent) {
            val chunk = event.chunk
            if (!isChunkInsideArea(chunk.x, chunk.z)) return

            requestChunkRebuild(chunk.x, chunk.z, event.world)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onChunkUnload(event: ChunkUnloadEvent) {
            val chunk = event.chunk
            if (!isChunkInsideArea(chunk.x, chunk.z)) return

            later {
                requestChunkRebuild(chunk.x, chunk.z, event.world)
            }
        }
    }
}
