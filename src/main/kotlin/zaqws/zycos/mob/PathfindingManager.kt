@file:Suppress("unused")

package zaqws.zycos.mob

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.AreaManager
import zaqws.zycos.Constants
import zaqws.zycos.CoroutineManager
import zaqws.zycos.CoroutineManager.scope
import zaqws.zycos.distanceSquared2D
import zaqws.zycos.fastRemoveIf
import zaqws.zycos.later
import zaqws.zycos.sync
import zaqws.zycos.toPosition
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class PathfindingManager {
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

    data class NavigationProfile(
        val mobHeight: Int = 2,
        val mobWidth: Double = 0.6,
        val maxStepUp: Int = 1,
        val maxStepDown: Int = 3
    ) {
        init {
            require(mobHeight > 0)
            require(mobWidth > 0.0)
            require(maxStepUp >= 0)
            require(maxStepDown >= 0)
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
        val navigationProfile: NavigationProfile = NavigationProfile()
    ) {
        val hierarchicalLock = ReentrantReadWriteLock()
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
        private val navigationProfile: NavigationProfile = NavigationProfile(),
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
        private val blockRadius = if (navigationProfile.mobWidth * 0.5 <= 0.5) 0 else 1

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
            if (start.raw == end.raw) return longArrayOf(start.raw)
            if (start !in area || end !in area) return LongArray(0)

            resetSearchState()

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
            if (deltaY > navigationProfile.maxStepUp || deltaY < -navigationProfile.maxStepDown) return false
            if (limitToCurrentChunk && (target.chunkX != current.chunkX || target.chunkZ != current.chunkZ)) return false
            if (!isWalkable(current, target)) return false
            if (deltaX != 0 && deltaZ != 0 && isDiagonalBlocked(current, deltaX, deltaZ, target.y)) return false

            return true
        }

        fun isStandable(position: AreaManager.Position): Boolean {
            if (!isReadableY(position.y - 1) || !isReadableY(position.y + navigationProfile.mobHeight - 1)) {
                return false
            }

            for (deltaZ in -blockRadius..blockRadius) {
                for (deltaX in -blockRadius..blockRadius) {
                    for (blockY in position.y until position.y + navigationProfile.mobHeight) {
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

                for (deltaY in navigationProfile.maxStepUp downTo -navigationProfile.maxStepDown) {
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
            while (currentRaw != -1L) {
                pathListCache.add(currentRaw)
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
            val maximumBodyY = max(current.y, target.y) + navigationProfile.mobHeight - 1

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
                val minimumClearanceY = current.y + navigationProfile.mobHeight
                val maximumClearanceY = target.y + navigationProfile.mobHeight - 1
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
            val maximumHeight = max(current.y, targetY) + navigationProfile.mobHeight - 1

            if (!isReadableY(minimumHeight) || !isReadableY(maximumHeight)) return true

            for (blockY in minimumHeight..maximumHeight) {
                if (getBlockMaterial(current.x + deltaX, blockY, current.z).isSolid) return true
                if (getBlockMaterial(current.x, blockY, current.z + deltaZ).isSolid) return true
            }

            return false
        }

        private fun getBlockMaterial(globalX: Int, globalY: Int, globalZ: Int): Material {
            if (!isReadableY(globalY)) return Material.AIR

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

            return snapshot?.getBlockType(globalX and 15, globalY, globalZ and 15) ?: Material.AIR
        }

        private fun isReadableY(globalY: Int): Boolean =
            globalY in minimumWorldHeight..< maximumWorldHeight

        private fun checkCancellation(cancellationJob: Job?, exploredNodes: Int) {
            if (cancellationJob == null || exploredNodes and CANCELLATION_CHECK_MASK != 0) return
            if (!cancellationJob.isActive) throw CancellationException()
        }
    }

    class GridRegistry {
        private companion object {
            const val CHUNK_LOCK_COUNT = 1024
            val AFFECTED_CHUNK_DELTA_X = intArrayOf(0, 1, -1, 0, 0, 1, 1, -1, -1)
            val AFFECTED_CHUNK_DELTA_Z = intArrayOf(0, 0, 0, 1, -1, 1, -1, 1, -1)
        }

        private data class BorderTransition(
            val source: AreaManager.Position,
            val target: AreaManager.Position,
            val canTraverseForward: Boolean,
            val canTraverseReverse: Boolean
        )

        private val gridRegistryMap = ConcurrentHashMap<String, HierarchicalGrid>()
        private val chunkLocks = Array(CHUNK_LOCK_COUNT) { Mutex() }

        fun getOrCreateGrid(
            identifier: String,
            area: AreaManager.Area,
            navigationProfile: NavigationProfile = NavigationProfile()
        ): HierarchicalGrid = gridRegistryMap.computeIfAbsent(identifier) {
            HierarchicalGrid(area, navigationProfile)
        }

        fun registerGrid(
            identifier: String,
            area: AreaManager.Area,
            navigationProfile: NavigationProfile = NavigationProfile()
        ): HierarchicalGrid = HierarchicalGrid(area, navigationProfile).also {
            gridRegistryMap[identifier] = it
        }

        fun removeGrid(identifier: String): HierarchicalGrid? =
            gridRegistryMap.remove(identifier)

        fun clear() {
            gridRegistryMap.clear()
        }

        fun captureAreaSnapshots(
            world: World,
            area: AreaManager.Area
        ): Long2ObjectMap<ChunkSnapshot> {
            val minimumChunkX = area.boundingBoxStart.chunkX
            val maximumChunkX = area.boundingBoxEnd.chunkX
            val minimumChunkZ = area.boundingBoxStart.chunkZ
            val maximumChunkZ = area.boundingBoxEnd.chunkZ
            val expectedChunkCount = (maximumChunkX - minimumChunkX + 1) * (maximumChunkZ - minimumChunkZ + 1)
            val snapshots = Long2ObjectOpenHashMap<ChunkSnapshot>(expectedChunkCount)

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
            centerChunkZ: Int
        ): Long2ObjectMap<ChunkSnapshot> {
            val snapshots = Long2ObjectOpenHashMap<ChunkSnapshot>(9)

            for (deltaX in -1..1) {
                for (deltaZ in -1..1) {
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
        ) = withContext(Dispatchers.Default) {
            hierarchicalGrid.hierarchicalLock.writeLock().lock()

            try {
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
                    navigationProfile = hierarchicalGrid.navigationProfile,
                    minimumWorldHeight = minimumWorldHeight,
                    maximumWorldHeight = maximumWorldHeight
                )
                val cancellationJob = coroutineContext[Job]

                for (cluster in hierarchicalGrid.clusters.values) {
                    bakeIntraEdges(
                        hierarchicalGrid,
                        cluster,
                        localPathfinder,
                        cancellationJob
                    )
                }
            } finally {
                hierarchicalGrid.hierarchicalLock.writeLock().unlock()
            }
        }

        suspend fun rebuildChunk(
            hierarchicalGrid: HierarchicalGrid,
            chunkX: Int,
            chunkZ: Int,
            world: World,
            plugin: JavaPlugin
        ) {
            val chunkKey = getChunkKey(chunkX, chunkZ)
            val mixedHash = (chunkKey xor (chunkKey ushr 32)).toInt()
            val positiveHash = if (mixedHash == Int.MIN_VALUE) 0 else abs(mixedHash)
            val mutex = chunkLocks[positiveHash % CHUNK_LOCK_COUNT]

            mutex.withLock {
                val neighborSnapshots = withContext(CoroutineManager.PaperDispatcher(plugin)) {
                    captureNeighborSnapshots(world, chunkX, chunkZ)
                }
                if (!neighborSnapshots.containsKey(chunkKey)) return

                withContext(Dispatchers.Default) {
                    hierarchicalGrid.hierarchicalLock.writeLock().lock()

                    try {
                        hierarchicalGrid.clearClusterData(chunkX, chunkZ)

                        scanChunkBorders(
                            hierarchicalGrid,
                            chunkX,
                            chunkZ,
                            neighborSnapshots,
                            world.minHeight,
                            world.maxHeight
                        )
                        scanChunkBorders(
                            hierarchicalGrid,
                            chunkX - 1,
                            chunkZ,
                            neighborSnapshots,
                            world.minHeight,
                            world.maxHeight
                        )
                        scanChunkBorders(
                            hierarchicalGrid,
                            chunkX,
                            chunkZ - 1,
                            neighborSnapshots,
                            world.minHeight,
                            world.maxHeight
                        )
                        scanChunkBorders(
                            hierarchicalGrid,
                            chunkX - 1,
                            chunkZ - 1,
                            neighborSnapshots,
                            world.minHeight,
                            world.maxHeight
                        )

                        val localPathfinder = LocalPathfinder(
                            neighborSnapshots,
                            limitToCurrentChunk = true,
                            navigationProfile = hierarchicalGrid.navigationProfile,
                            minimumWorldHeight = world.minHeight,
                            maximumWorldHeight = world.maxHeight
                        )
                        val cancellationJob = coroutineContext[Job]

                        for (index in AFFECTED_CHUNK_DELTA_X.indices) {
                            val affectedChunkX = chunkX + AFFECTED_CHUNK_DELTA_X[index]
                            val affectedChunkZ = chunkZ + AFFECTED_CHUNK_DELTA_Z[index]
                            val cluster = hierarchicalGrid.clusters[
                                getChunkKey(affectedChunkX, affectedChunkZ)
                            ] ?: continue

                            bakeIntraEdges(
                                hierarchicalGrid,
                                cluster,
                                localPathfinder,
                                cancellationJob
                            )
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
            if (!snapshots.containsKey(getChunkKey(chunkX, chunkZ))) return

            val currentCluster = grid.getOrCreateCluster(chunkX, chunkZ)
            val borderStartX = chunkX shl Constants.CHUNK_SHIFT
            val borderStartZ = chunkZ shl Constants.CHUNK_SHIFT
            val transitionPathfinder = LocalPathfinder(
                snapshots,
                limitToCurrentChunk = false,
                navigationProfile = grid.navigationProfile,
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
            val profile = grid.navigationProfile

            for (sourceY in grid.area.boundingBoxStart.y..grid.area.boundingBoxEnd.y) {
                for (deltaY in profile.maxStepUp downTo -profile.maxStepDown) {
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
            pathfinder: LocalPathfinder,
            cancellationJob: Job?
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
                    grid.area,
                    cancellationJob
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
            if (source.raw == target.raw) return@withContext longArrayOf(source.raw)
            if (source !in grid.area || target !in grid.area) return@withContext LongArray(0)

            val cancellationJob = coroutineContext[Job]
            val localPathfinder = LocalPathfinder(
                chunkSnapshots,
                limitToCurrentChunk = true,
                navigationProfile = grid.navigationProfile,
                minimumWorldHeight = minimumWorldHeight,
                maximumWorldHeight = maximumWorldHeight
            )

            if (source.chunkX == target.chunkX && source.chunkZ == target.chunkZ) {
                val directPath = localPathfinder.findPath(
                    source,
                    target,
                    grid.area,
                    maxNodes = 256,
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
                    val currentEntrance = if (currentPositionRaw == source.raw) {
                        null
                    } else {
                        grid.entrances[currentPositionRaw] ?: continue
                    }
                    val currentPosition = currentEntrance?.position ?: source
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
                        continue
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

            while (currentRaw != -1L) {
                pathList.add(currentRaw)
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
            const val FAILURE_RETRY_DELAY_MILLISECONDS = 1000L
        }

        private val hierarchicalPathfinder = HierarchicalPathfinder()
        private val mobHeight = ceil(entity.height).toInt()
        private val mobWidth = entity.width

        private var macroPath: LongArray? = null
        private var localPath: LongArray? = null
        private var macroIndex = 0
        private var localIndex = 0
        private var searchJob: Job? = null
        private var localSearchJob: Job? = null
        private var lastTargetLocation: Location? = null
        private var latestSnapshots: Long2ObjectMap<ChunkSnapshot>? = null
        private var lastFailureTime = 0L
        private var requestGeneration = 0L

        var speed: Double = 1.0
            set(value) {
                if (field == value) return
                field = value
                triggerMove()
            }

        var failed = false
            private set

        init {
            require(mobHeight <= hierarchicalGrid.navigationProfile.mobHeight) {
                "The hierarchical grid navigation profile is shorter than the mob."
            }
            require(mobWidth <= hierarchicalGrid.navigationProfile.mobWidth + COST_EPSILON) {
                "The hierarchical grid navigation profile is narrower than the mob."
            }
        }

        fun navigateTo(targetLocation: Location) {
            if (!entity.isValid || entity.isDead) return

            if (targetLocation.world != entity.world || targetLocation.toPosition() !in hierarchicalGrid.area) {
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
                if (entity.location.distanceSquared(targetLocation) <= TARGET_REUSE_DISTANCE_SQUARED) return
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

            if (localPath != null && localIndex < localPath!!.size) {
                if (!entity.pathfinder.hasPath()) triggerMove()
                return
            }

            if (macroIndex >= activeMacroPath.size - 1) {
                macroPath = null
                localPath = null
                return
            }

            if (localSearchJob?.isActive == true) return
            requestLocalPath(targetLocation, activeMacroPath)
        }

        fun cancel() {
            stopNavigation()
        }

        private fun requestLocalPath(
            targetLocation: Location,
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
                navigationProfile = NavigationProfile(
                    mobHeight = mobHeight,
                    mobWidth = mobWidth,
                    maxStepUp = hierarchicalGrid.navigationProfile.maxStepUp,
                    maxStepDown = hierarchicalGrid.navigationProfile.maxStepDown
                ),
                minimumWorldHeight = entity.world.minHeight,
                maximumWorldHeight = entity.world.maxHeight
            )

            val sourcePosition = entity.location.toPosition()
            val generation = requestGeneration

            localSearchJob?.cancel()
            localSearchJob = scope.launch {
                val generatedPath = withContext(Dispatchers.Default) {
                    pathfinder.findPath(
                        sourcePosition,
                        nextMacroNode,
                        hierarchicalGrid.area,
                        cancellationJob = coroutineContext[Job]
                    )
                }

                sync {
                    if (generation != requestGeneration) return@sync
                    if (!entity.isValid || entity.isDead) return@sync

                    if (generatedPath.isEmpty()) {
                        registerFailure()
                        macroPath = null
                        localPath = null
                        latestSnapshots = null
                        return@sync
                    }

                    failed = false

                    if (generatedPath.size == 1) {
                        macroIndex++
                        localPath = null
                        localIndex = 0
                        navigateTo(targetLocation)
                        return@sync
                    }

                    localPath = generatedPath
                    localIndex = 1
                    triggerMove()
                }
            }
        }

        private fun advanceLocalPathIfReached(targetLocation: Location) {
            val activeLocalPath = localPath ?: return
            if (localIndex >= activeLocalPath.size) return

            val currentPosition = entity.location.toPosition()
            val targetNodePosition = AreaManager.Position(activeLocalPath[localIndex])
            val targetNodeCenter = targetNodePosition
                .toLocation(entity.world)
                .add(0.5, 0.0, 0.5)

            if (entity.location.distanceSquared2D(targetNodeCenter) >= WAYPOINT_REACHED_DISTANCE_SQUARED ||
                abs(currentPosition.y - targetNodePosition.y) > 1
            ) {
                return
            }

            localIndex++

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
            val currentChunkKey = getChunkKey(currentChunkX, currentChunkZ)
            val nextChunkKey = getChunkKey(nextChunkX, nextChunkZ)
            val cachedSnapshots = latestSnapshots

            if (cachedSnapshots != null &&
                cachedSnapshots.containsKey(currentChunkKey) &&
                cachedSnapshots.containsKey(nextChunkKey)
            ) {
                return cachedSnapshots
            }

            return Long2ObjectOpenHashMap<ChunkSnapshot>(18).also { snapshots ->
                snapshots.putAll(
                    gridRegistry.captureNeighborSnapshots(entity.world, currentChunkX, currentChunkZ)
                )
                snapshots.putAll(
                    gridRegistry.captureNeighborSnapshots(entity.world, nextChunkX, nextChunkZ)
                )
                latestSnapshots = snapshots
            }
        }

        private fun triggerMove() {
            val activeLocalPath = localPath ?: return
            if (localIndex >= activeLocalPath.size) return

            val nextNodeLocation = AreaManager.Position(activeLocalPath[localIndex])
                .toLocation(entity.world)
                .add(0.5, 0.0, 0.5)

            entity.pathfinder.moveTo(nextNodeLocation, speed)
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
            macroPath = null
            localPath = null
            macroIndex = 0
            localIndex = 0

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
            latestSnapshots = snapshots

            searchJob = scope.launch {
                val resultPath = hierarchicalPathfinder.findHierarchicalPath(
                    sourcePosition,
                    targetPosition,
                    hierarchicalGrid,
                    snapshots,
                    entity.world.minHeight,
                    entity.world.maxHeight
                )

                sync {
                    if (generation != requestGeneration) return@sync
                    if (!entity.isValid || entity.isDead) return@sync

                    if (resultPath.isEmpty()) {
                        registerFailure()
                        macroPath = null
                        localPath = null
                        latestSnapshots = null
                        return@sync
                    }

                    failed = false
                    macroPath = resultPath
                    macroIndex = 0
                    localPath = null
                    localIndex = 0
                    navigateTo(targetLocation)
                }
            }
        }

        private fun registerFailure() {
            failed = true
            lastFailureTime = System.currentTimeMillis()
        }

        private fun canRetryPathfinding(): Boolean =
            !failed || System.currentTimeMillis() - lastFailureTime >= FAILURE_RETRY_DELAY_MILLISECONDS

        private fun stopNavigation() {
            requestGeneration++
            searchJob?.cancel()
            localSearchJob?.cancel()
            searchJob = null
            localSearchJob = null
            macroPath = null
            localPath = null
            macroIndex = 0
            localIndex = 0
            latestSnapshots = null
            lastTargetLocation = null
            failed = false
            entity.pathfinder.stopPathfinding()
        }
    }

    class PathfindingUpdateListener(
        private val plugin: JavaPlugin,
        private val gridRegistry: GridRegistry,
        private val hierarchicalGrid: HierarchicalGrid
    ) : Listener {
        private val rebuildJobs = ConcurrentHashMap<Long, Job>()

        private fun updateGridAt(location: Location) {
            val position = location.toPosition()
            if (position !in hierarchicalGrid.area) return

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
            chunkX in hierarchicalGrid.area.boundingBoxStart.chunkX..hierarchicalGrid.area.boundingBoxEnd.chunkX &&
                    chunkZ in hierarchicalGrid.area.boundingBoxStart.chunkZ..hierarchicalGrid.area.boundingBoxEnd.chunkZ

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
    }
}
