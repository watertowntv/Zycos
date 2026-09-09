@file:Suppress("unused")

package zaqws.zycos.mob

import com.destroystokyo.paper.entity.Pathfinder
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


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
        @Volatile
        internal var revision = 0L
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

    data class LocalTransition(
        val sourceRaw: Long,
        val targetRaw: Long
    )

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
        private var unavailableGeometry = false

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
            cancellationJob: Job? = null,
            blockedPositions: LongSet? = null,
            blockedTransitions: Set<LocalTransition>? = null
        ): LongArray {
            if (start !in area || end !in area) return LongArray(0)

            resetSearchState()
            cancellationJob?.ensureActive()
            if (!isStandable(start) || !isStandable(end)) return LongArray(0)
            if (blockedPositions?.contains(end.raw) == true) return LongArray(0)
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
                    navigationParentMap,
                    blockedPositions,
                    blockedTransitions
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
                    null,
                    null,
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

        fun getStandingFloorY(position: AreaManager.Position): Double? =
            if (isStandable(position)) position.y.toDouble() else null

        fun encounteredUnavailableGeometry(): Boolean = unavailableGeometry

        private fun resetSearchState() {
            openSet.clear()
            accumulatedCostMap.clear()
            navigationParentMap.clear()
            lastChunkKey = Long.MIN_VALUE
            lastSnapshot = null
            unavailableGeometry = false
        }

        private fun expandNeighbors(
            current: AreaManager.Position,
            end: AreaManager.Position?,
            area: AreaManager.Area,
            parentMap: Long2LongOpenHashMap?,
            blockedPositions: LongSet?,
            blockedTransitions: Set<LocalTransition>?
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

                    if (blockedPositions?.contains(target.raw) == true) continue
                    if (blockedTransitions?.contains(LocalTransition(current.raw, target.raw)) == true) continue
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

            if (snapshot == null) {
                unavailableGeometry = true
                return Material.BEDROCK
            }

            return snapshot.getBlockType(globalX and 15, globalY, globalZ and 15)
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
                    hierarchicalGrid.revision++
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
                        hierarchicalGrid.revision++
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
            maximumWorldHeight: Int = DEFAULT_MAXIMUM_WORLD_HEIGHT,
            blockedEntranceIds: LongSet? = null
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
                                openSet,
                                blockedEntranceIds
                            )
                        }
                    }

                    if (currentEntrance != null && blockedEntranceIds?.contains(currentPositionRaw) == true) {
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
                            openSet,
                            blockedEntranceIds
                        )
                        expandEntranceEdges(
                            currentEntrance.interEdges,
                            currentPositionRaw,
                            currentAccumulatedCost,
                            target,
                            grid,
                            accumulatedCostMap,
                            navigationParentMap,
                            openSet,
                            blockedEntranceIds
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
            openSet: PriorityQueue<MacroPathNode>,
            blockedEntranceIds: LongSet?
        ) {
            val entranceIds = sourceCluster.entranceIds
            val costs = localPathfinder.findCostsToAllEntrances(
                source,
                entranceIds,
                grid.area,
                cancellationJob
            )

            for (entranceId in entranceIds) {
                if (blockedEntranceIds?.contains(entranceId) == true) continue
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
            openSet: PriorityQueue<MacroPathNode>,
            blockedEntranceIds: LongSet?
        ) {
            for ((targetEntranceId, cost) in edges) {
                if (blockedEntranceIds?.contains(targetEntranceId) == true) continue
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
        private val entity: Mob,
        private val hierarchicalGrid: HierarchicalGrid,
        private val scope: CoroutineScope,
        private val gridRegistry: GridRegistry
    ) {
        private companion object {
            const val MOVE_TARGET_LOOKAHEAD_NODES = 4
            const val EXECUTOR_RETRIES_BEFORE_LOCAL_REPLAN = 2
            const val LOCAL_FAILURES_BEFORE_MACRO_REPLAN = 2
            const val MAX_BLOCKED_ENTRANCES = 8
            const val EXECUTOR_RETRY_DELAY_MILLISECONDS = 100L
            const val LOCAL_RETRY_DELAY_MILLISECONDS = 200L
            const val CHUNK_RETRY_DELAY_MILLISECONDS = 250L
            const val MACRO_RETRY_DELAY_MILLISECONDS = 750L
            const val STUCK_TIMEOUT_NANOSECONDS = 3_000_000_000L
            const val EXECUTOR_INACTIVE_GRACE_NANOSECONDS = 250_000_000L
            const val MINIMUM_PROGRESS_DISTANCE = 0.15
            const val MINIMUM_PROGRESS_DISTANCE_SQUARED = MINIMUM_PROGRESS_DISTANCE * MINIMUM_PROGRESS_DISTANCE
            const val EXECUTOR_PATH_LENGTH_FACTOR = 2.5
            const val EXECUTOR_PATH_LENGTH_ALLOWANCE = 2.0
            const val EXECUTOR_MINIMUM_MAX_PATH_LENGTH = 4.0
            const val EXECUTOR_DEVIATION_DISTANCE = 2.5
            const val EXECUTOR_DEVIATION_DISTANCE_SQUARED = EXECUTOR_DEVIATION_DISTANCE * EXECUTOR_DEVIATION_DISTANCE
            const val EXECUTOR_CORRIDOR_DISTANCE = 1.5
            const val EXECUTOR_CORRIDOR_DISTANCE_SQUARED = EXECUTOR_CORRIDOR_DISTANCE * EXECUTOR_CORRIDOR_DISTANCE
            const val LOCAL_SEARCH_MAX_NODES = 16384
            const val LOCAL_SEARCH_MAXIMUM_CHUNK_DELTA = 1
            const val BLOCK_CENTER_OFFSET = 0.5
            const val BODY_SURFACE_BASE_TOLERANCE = 0.125
            const val BODY_SURFACE_MAXIMUM_RISE = 0.9375
            const val SURFACE_EPSILON = 1e-6
            const val LOGICAL_POSITION_VERTICAL_DISTANCE = 0.75
            const val NODE_OCCUPANCY_VERTICAL_TOLERANCE = 0.25
            const val FINAL_REACHED_FLOOR_TOLERANCE = 0.35
            const val PAPER_ENDPOINT_MISMATCHES_BEFORE_TRANSITION_BLOCK = 2
            const val LIVE_GEOMETRY_FAILURES_BEFORE_TRANSITION_BLOCK = 3
            const val EXECUTOR_LANDING_GRACE_NANOSECONDS = 750_000_000L
            const val MINIMUM_DESCENDING_VELOCITY = 0.01
            const val ANCHOR_SUPPORT_VERTICAL_TOLERANCE = 0.1875
            const val TARGET_ANCHOR_CONTINUITY_NANOSECONDS = 1_500_000_000L
            const val ANCHOR_PREFERRED_MIN_OVERLAP_RATIO = 0.15
            const val TARGET_AREA_MARGIN = 1.0
        }

        private enum class Phase {
            IDLE,
            NEED_MACRO,
            PLANNING_MACRO,
            NEED_LOCAL,
            PLANNING_LOCAL,
            READY_TO_EXECUTE,
            EXECUTING,
            RETRY,
            ARRIVED
        }

        private enum class ExecutorFailureReason {
            PATH_UNAVAILABLE,
            PARTIAL_PATH,
            LIVE_GEOMETRY_INVALID,
            PAPER_ROUTE_OUTSIDE_CORRIDOR,
            ROUTE_DEVIATION,
            START_REJECTED,
            PAPER_ENDPOINT_MISMATCH,
            ENDED_EARLY
        }

        private data class NavigationAnchor(
            val position: AreaManager.Position,
            val floorY: Double
        )

        private sealed interface AnchorResolution {
            data class Grounded(val anchor: NavigationAnchor) : AnchorResolution
            data object Airborne : AnchorResolution
            data object Unavailable : AnchorResolution
            data object OutsideArea : AnchorResolution
        }

        private data class SupportCandidate(
            val anchor: NavigationAnchor,
            val verticalGap: Double,
            val containsCenter: Boolean,
            val horizontalDistanceSquared: Double,
            val overlapArea: Double,
            val preferred: Boolean
        )

        private sealed interface LocalPlanResult {
            class Success(val nodes: LongArray) : LocalPlanResult
            data object NoPath : LocalPlanResult
            data object RequiredChunkUnavailable : LocalPlanResult
        }

        private class MacroRoute(
            val nodes: LongArray,
            var reachedIndex: Int,
            val targetChunkX: Int,
            val targetChunkZ: Int,
            val gridRevision: Long
        )

        private class LocalRoute(
            val nodes: LongArray,
            var reachedIndex: Int,
            val macroObjectiveIndex: Int,
            val finalLeg: Boolean
        )

        private data class ActiveExecutionSegment(
            val targetLocalIndex: Int,
            val targetPosition: AreaManager.Position,
            val physicalTargetLocation: Location,
            val acceptedPathPoints: List<Location>,
            var lastNextPointIndex: Int,
            var lastProgressLocation: Location,
            var lastProgressTime: Long,
            var inactiveSince: Long = 0L
        )

        private val hierarchicalPathfinder = HierarchicalPathfinder()
        private val mobHeight = ceil(entity.height).toInt()
        private val mobWidth = entity.width
        private val finalReachedHorizontalDistanceSquared = mobWidth * mobWidth
        private val worldId = entity.world.uid

        private var phase = Phase.IDLE
        private var retryResumePhase = Phase.NEED_MACRO
        private var retryAt = 0L
        private var navigationEpoch = 0L
        private var planningJob: Job? = null
        private var desiredTargetLocation: Location? = null
        private var currentAnchorResolution: AnchorResolution = AnchorResolution.Airborne
        private var stableTargetAnchor: NavigationAnchor? = null
        private var targetAnchorMissingSince = 0L
        private var macroRoute: MacroRoute? = null
        private var localRoute: LocalRoute? = null
        private var activeExecutionSegment: ActiveExecutionSegment? = null
        private var executorFailureCount = 0
        private var localFailureCount = 0
        private val blockedTransitions = HashSet<LocalTransition>()
        private val paperEndpointMismatchCounts = HashMap<LocalTransition, Int>()
        private val liveGeometryFailureCounts = HashMap<LocalTransition, Int>()
        private val blockedEntranceIds = LongOpenHashSet()

        var speed: Double = 1.0
            set(value) {
                require(value.isFinite() && value > 0.0)
                if (field == value) return
                field = value

                if (phase == Phase.EXECUTING) {
                    entity.pathfinder.stopPathfinding()
                    activeExecutionSegment = null
                    phase = if (localRoute == null) Phase.NEED_LOCAL else Phase.READY_TO_EXECUTE
                }
            }

        init {
            require(mobHeight <= hierarchicalGrid.mobPathfindingProfile.mobHeight) {
                "The hierarchical grid navigation profile is shorter than the mob."
            }
            require(mobWidth <= hierarchicalGrid.mobPathfindingProfile.mobWidth + COST_EPSILON) {
                "The hierarchical grid navigation profile is narrower than the mob."
            }
        }

        fun navigateTo(targetLocation: Location) {
            if (!isEntityUsable()) {
                stopNavigation()
                return
            }
            if (!isTargetUsable(targetLocation)) {
                stopNavigation()
                return
            }

            val firstTarget = desiredTargetLocation == null
            desiredTargetLocation = targetLocation.clone()

            if (firstTarget) {
                navigationEpoch++
                phase = Phase.NEED_MACRO
            }
        }

        fun tick() {
            if (!isEntityUsable()) {
                stopNavigation()
                return
            }

            val target = desiredTargetLocation ?: run {
                phase = Phase.IDLE
                return
            }
            if (!isTargetUsable(target)) {
                stopNavigation()
                return
            }

            refreshCurrentEntityAnchor()
            refreshStableTargetAnchor(target)

            val targetAnchor = stableTargetAnchor
            val activeMacroRoute = macroRoute
            if (activeMacroRoute != null) {
                if (activeMacroRoute.gridRevision != hierarchicalGrid.revision ||
                    targetAnchor == null ||
                    !isTargetInMacroChunk(activeMacroRoute, targetAnchor.position)
                ) {
                    invalidateMacroRoute(clearTransitionEvidence = true)
                }
            }

            if (targetAnchor != null && isLatestTargetReached()) {
                enterArrivedState()
                return
            }

            if (phase == Phase.ARRIVED) {
                phase = if (macroRoute == null) Phase.NEED_MACRO else Phase.NEED_LOCAL
            }

            if (phase == Phase.RETRY) {
                if (System.nanoTime() < retryAt) return
                phase = retryResumePhase
            }

            when (phase) {
                Phase.IDLE -> phase = Phase.NEED_MACRO
                Phase.NEED_MACRO -> {
                    if (currentGroundedAnchor() != null && stableTargetAnchor != null) {
                        requestMacroPath()
                    }
                }

                Phase.PLANNING_MACRO -> {
                    if (planningJob?.isActive != true) {
                        planningJob = null
                        phase = Phase.NEED_MACRO
                    }
                }
                Phase.NEED_LOCAL -> {
                    if (currentGroundedAnchor() != null && stableTargetAnchor != null) {
                        requestLocalPath()
                    }
                }

                Phase.PLANNING_LOCAL -> {
                    if (planningJob?.isActive != true) {
                        planningJob = null
                        phase = Phase.NEED_LOCAL
                    }
                }
                Phase.READY_TO_EXECUTE -> {
                    if (currentGroundedAnchor() != null || entity.isOnGround) {
                        triggerMove()
                    }
                }

                Phase.EXECUTING -> updateExecution(System.nanoTime())
                Phase.RETRY -> Unit
                Phase.ARRIVED -> Unit
            }
        }

        fun cancel() {
            stopNavigation()
        }

        private fun requestMacroPath() {
            if (phase == Phase.PLANNING_MACRO || planningJob?.isActive == true) return

            val sourceAnchor = currentGroundedAnchor() ?: return
            val targetAnchor = stableTargetAnchor ?: return
            val sourcePosition = sourceAnchor.position
            val targetPosition = targetAnchor.position

            if (!entity.world.isChunkLoaded(sourcePosition.chunkX, sourcePosition.chunkZ) ||
                !entity.world.isChunkLoaded(targetPosition.chunkX, targetPosition.chunkZ)
            ) {
                scheduleRetry(CHUNK_RETRY_DELAY_MILLISECONDS, Phase.NEED_MACRO)
                return
            }

            val snapshots = obtainLocalSnapshots(
                sourcePosition.chunkX,
                sourcePosition.chunkZ,
                targetPosition.chunkX,
                targetPosition.chunkZ
            )
            val worldMinimumHeight = entity.world.minHeight
            val worldMaximumHeight = entity.world.maxHeight
            val requestEpoch = navigationEpoch
            val requestGridRevision = hierarchicalGrid.revision
            val requestTargetChunkX = targetPosition.chunkX
            val requestTargetChunkZ = targetPosition.chunkZ
            val blockedEntranceSnapshot: LongSet? = if (blockedEntranceIds.isEmpty()) {
                null
            } else {
                LongOpenHashSet(blockedEntranceIds)
            }

            phase = Phase.PLANNING_MACRO
            planningJob?.cancel()
            planningJob = scope.launch {
                val resultPath = hierarchicalPathfinder.findHierarchicalPath(
                    sourcePosition,
                    targetPosition,
                    hierarchicalGrid,
                    snapshots,
                    worldMinimumHeight,
                    worldMaximumHeight,
                    blockedEntranceSnapshot
                )

                if (!isCurrentRequest(requestEpoch) || !isEntityUsable()) return@launch
                if (requestGridRevision != hierarchicalGrid.revision) {
                    planningJob = null
                    invalidateMacroRoute(clearTransitionEvidence = true)
                    return@launch
                }

                val liveSource = refreshCurrentEntityAnchor()
                if (liveSource !is AnchorResolution.Grounded ||
                    liveSource.anchor.position.chunkX != sourcePosition.chunkX ||
                    liveSource.anchor.position.chunkZ != sourcePosition.chunkZ
                ) {
                    planningJob = null
                    phase = Phase.NEED_MACRO
                    return@launch
                }

                val latestTargetAnchor = stableTargetAnchor
                if (latestTargetAnchor == null ||
                    latestTargetAnchor.position.chunkX != requestTargetChunkX ||
                    latestTargetAnchor.position.chunkZ != requestTargetChunkZ
                ) {
                    planningJob = null
                    invalidateMacroRoute(clearTransitionEvidence = true)
                    return@launch
                }

                planningJob = null
                if (resultPath.isEmpty()) {
                    handleHierarchicalRouteUnavailable()
                    return@launch
                }

                macroRoute = MacroRoute(
                    nodes = resultPath,
                    reachedIndex = 0,
                    targetChunkX = requestTargetChunkX,
                    targetChunkZ = requestTargetChunkZ,
                    gridRevision = requestGridRevision
                )
                localRoute = null
                activeExecutionSegment = null
                executorFailureCount = 0
                localFailureCount = 0
                blockedTransitions.clear()
                paperEndpointMismatchCounts.clear()
                liveGeometryFailureCounts.clear()
                phase = Phase.NEED_LOCAL
            }
        }

        private fun requestLocalPath() {
            if (phase == Phase.PLANNING_LOCAL || planningJob?.isActive == true) return

            val activeMacroRoute = macroRoute ?: run {
                phase = Phase.NEED_MACRO
                return
            }
            if (activeMacroRoute.nodes.isEmpty()) {
                handleHierarchicalRouteUnavailable()
                return
            }
            if (activeMacroRoute.gridRevision != hierarchicalGrid.revision) {
                invalidateMacroRoute(clearTransitionEvidence = true)
                return
            }

            val sourceAnchor = currentGroundedAnchor() ?: return
            val sourcePosition = sourceAnchor.position
            val macroObjectiveIndex = if (activeMacroRoute.reachedIndex >= activeMacroRoute.nodes.lastIndex) {
                activeMacroRoute.nodes.lastIndex
            } else {
                activeMacroRoute.reachedIndex + 1
            }
            val finalLeg = macroObjectiveIndex == activeMacroRoute.nodes.lastIndex
            val plannedObjective = AreaManager.Position(activeMacroRoute.nodes[macroObjectiveIndex])
            val targetAnchor = if (finalLeg) stableTargetAnchor ?: return else null
            val targetPosition = targetAnchor?.position ?: plannedObjective

            if (!entity.world.isChunkLoaded(sourcePosition.chunkX, sourcePosition.chunkZ) ||
                !entity.world.isChunkLoaded(targetPosition.chunkX, targetPosition.chunkZ)
            ) {
                scheduleRetry(CHUNK_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
                return
            }

            val snapshots = obtainLocalSnapshots(
                sourcePosition.chunkX,
                sourcePosition.chunkZ,
                targetPosition.chunkX,
                targetPosition.chunkZ
            )
            val worldMinimumHeight = entity.world.minHeight
            val worldMaximumHeight = entity.world.maxHeight
            val requestEpoch = navigationEpoch
            val requestGridRevision = hierarchicalGrid.revision
            val blockedTransitionSnapshot = if (blockedTransitions.isEmpty()) {
                null
            } else {
                HashSet(blockedTransitions)
            }

            phase = Phase.PLANNING_LOCAL
            planningJob?.cancel()
            planningJob = scope.launch {
                val searchResult = withContext(Dispatchers.Default) {
                    val pathfinder = LocalPathfinder(
                        snapshots,
                        limitToCurrentChunk = false,
                        mobPathfindingProfile = MobPathfindingProfile(
                            mobHeight = mobHeight,
                            mobWidth = mobWidth,
                            maxStepUp = hierarchicalGrid.mobPathfindingProfile.maxStepUp,
                            maxStepDown = hierarchicalGrid.mobPathfindingProfile.maxStepDown
                        ),
                        minimumWorldHeight = worldMinimumHeight,
                        maximumWorldHeight = worldMaximumHeight
                    )
                    val path = pathfinder.findPath(
                        sourcePosition,
                        targetPosition,
                        createLocalSearchArea(sourcePosition, targetPosition)
                            ?: return@withContext LocalPlanResult.NoPath,
                        maxNodes = LOCAL_SEARCH_MAX_NODES,
                        cancellationJob = coroutineContext[Job],
                        blockedTransitions = blockedTransitionSnapshot
                    )

                    if (path.isNotEmpty()) {
                        LocalPlanResult.Success(path)
                    } else if (pathfinder.encounteredUnavailableGeometry()) {
                        LocalPlanResult.RequiredChunkUnavailable
                    } else {
                        LocalPlanResult.NoPath
                    }
                }

                if (!isCurrentRequest(requestEpoch) || !isEntityUsable()) return@launch
                if (macroRoute !== activeMacroRoute || requestGridRevision != hierarchicalGrid.revision) {
                    planningJob = null
                    invalidateMacroRoute(clearTransitionEvidence = true)
                    return@launch
                }

                val liveSource = refreshCurrentEntityAnchor()
                if (liveSource !is AnchorResolution.Grounded ||
                    abs(liveSource.anchor.position.x - sourcePosition.x) > 1 ||
                    abs(liveSource.anchor.position.y - sourcePosition.y) > 1 ||
                    abs(liveSource.anchor.position.z - sourcePosition.z) > 1
                ) {
                    planningJob = null
                    phase = Phase.NEED_LOCAL
                    return@launch
                }

                planningJob = null
                when (searchResult) {
                    LocalPlanResult.NoPath -> handleLocalRouteUnavailable()
                    LocalPlanResult.RequiredChunkUnavailable ->
                        scheduleRetry(CHUNK_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)

                    is LocalPlanResult.Success -> {
                        val nodes = searchResult.nodes
                        localRoute = LocalRoute(
                            nodes = nodes,
                            reachedIndex = if (nodes.size == 1) -1 else 0,
                            macroObjectiveIndex = macroObjectiveIndex,
                            finalLeg = finalLeg
                        )
                        activeExecutionSegment = null
                        executorFailureCount = 0
                        phase = Phase.READY_TO_EXECUTE
                    }
                }
            }
        }

        private fun triggerMove() {
            val activeLocalRoute = localRoute ?: run {
                phase = Phase.NEED_LOCAL
                return
            }
            if (activeExecutionSegment != null) {
                phase = Phase.EXECUTING
                return
            }
            synchronizeLocalProgress(activeLocalRoute, activeLocalRoute.nodes.lastIndex)
            if (activeLocalRoute.reachedIndex >= activeLocalRoute.nodes.lastIndex) {
                completeLocalRoute(activeLocalRoute)
                return
            }

            val nextIndex = activeLocalRoute.reachedIndex + 1
            val immediateTarget = AreaManager.Position(activeLocalRoute.nodes[nextIndex])
            if (!entity.world.isChunkLoaded(immediateTarget.chunkX, immediateTarget.chunkZ)) {
                scheduleRetry(CHUNK_RETRY_DELAY_MILLISECONDS, Phase.READY_TO_EXECUTE)
                return
            }

            val lookaheadNodes = MOVE_TARGET_LOOKAHEAD_NODES.coerceAtLeast(1)
            val maximumTargetIndex = determineMaximumExecutionTargetIndex(
                activeLocalRoute,
                nextIndex,
                lookaheadNodes
            )
            val candidateIndices = if (maximumTargetIndex == nextIndex) {
                intArrayOf(nextIndex)
            } else {
                intArrayOf(maximumTargetIndex, nextIndex)
            }
            var failureReason = ExecutorFailureReason.PATH_UNAVAILABLE

            for (candidateIndex in candidateIndices) {
                val candidatePosition = AreaManager.Position(activeLocalRoute.nodes[candidateIndex])
                if (!entity.world.isChunkLoaded(candidatePosition.chunkX, candidatePosition.chunkZ)) continue

                val invalidTransition = findInvalidLiveTransition(activeLocalRoute, candidateIndex)
                if (invalidTransition != null) {
                    recordLiveGeometryFailure(invalidTransition)
                    invalidateLocalRoute()
                    scheduleRetry(LOCAL_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
                    return
                }

                val physicalTargetLocation = resolveExecutionTargetLocation(candidatePosition)
                if (physicalTargetLocation == null) {
                    failureReason = ExecutorFailureReason.LIVE_GEOMETRY_INVALID
                    continue
                }

                val candidatePath = entity.pathfinder.findPath(physicalTargetLocation)
                if (candidatePath == null) {
                    failureReason = ExecutorFailureReason.PATH_UNAVAILABLE
                    continue
                }
                if (!candidatePath.canReachFinalPoint()) {
                    failureReason = ExecutorFailureReason.PARTIAL_PATH
                    continue
                }
                if (!isPaperFinalPointCompatible(
                        candidatePath.finalPoint,
                        candidatePosition
                    )
                ) {
                    failureReason = ExecutorFailureReason.PAPER_ENDPOINT_MISMATCH
                    continue
                }
                if (!isPaperPathBounded(
                        candidatePath,
                        activeLocalRoute,
                        activeLocalRoute.reachedIndex,
                        candidateIndex
                    )
                ) {
                    failureReason = ExecutorFailureReason.PAPER_ROUTE_OUTSIDE_CORRIDOR
                    continue
                }
                if (!entity.pathfinder.moveTo(candidatePath, speed)) {
                    failureReason = ExecutorFailureReason.START_REJECTED
                    continue
                }

                immediateTransition(activeLocalRoute)?.let {
                    paperEndpointMismatchCounts.remove(it)
                }

                val currentLocation = entity.location
                val activePaperPath = entity.pathfinder.currentPath ?: candidatePath
                val acceptedPathPoints = ArrayList<Location>(activePaperPath.points.size + 2)
                acceptedPathPoints.add(currentLocation.clone())
                for (point in activePaperPath.points) {
                    acceptedPathPoints.add(point.clone())
                }
                acceptedPathPoints.add(physicalTargetLocation.clone())

                activeExecutionSegment = ActiveExecutionSegment(
                    targetLocalIndex = candidateIndex,
                    targetPosition = candidatePosition,
                    physicalTargetLocation = physicalTargetLocation,
                    acceptedPathPoints = acceptedPathPoints,
                    lastNextPointIndex = activePaperPath.nextPointIndex,
                    lastProgressLocation = currentLocation,
                    lastProgressTime = System.nanoTime()
                )
                phase = Phase.EXECUTING
                return
            }

            handleExecutorFailure(failureReason)
        }

        private fun updateExecution(currentTime: Long) {
            val activeLocalRoute = localRoute ?: run {
                entity.pathfinder.stopPathfinding()
                activeExecutionSegment = null
                phase = Phase.NEED_LOCAL
                return
            }
            val executionSegment = activeExecutionSegment ?: run {
                phase = Phase.READY_TO_EXECUTE
                return
            }
            if (executionSegment.targetLocalIndex !in activeLocalRoute.reachedIndex + 1..activeLocalRoute.nodes.lastIndex ||
                activeLocalRoute.nodes[executionSegment.targetLocalIndex] != executionSegment.targetPosition.raw
            ) {
                entity.pathfinder.stopPathfinding()
                activeExecutionSegment = null
                phase = Phase.READY_TO_EXECUTE
                return
            }

            synchronizeLocalProgress(activeLocalRoute, executionSegment.targetLocalIndex)
            if (activeLocalRoute.reachedIndex >= executionSegment.targetLocalIndex) {
                entity.pathfinder.stopPathfinding()
                activeExecutionSegment = null
                executorFailureCount = 0
                if (activeLocalRoute.reachedIndex >= activeLocalRoute.nodes.lastIndex) {
                    completeLocalRoute(activeLocalRoute)
                } else {
                    phase = Phase.READY_TO_EXECUTE
                }
                return
            }

            val currentLocation = entity.location
            val currentPaperPath = entity.pathfinder.currentPath
            if (currentPaperPath != null && entity.pathfinder.hasPath()) {
                if (!isPaperFinalPointCompatible(
                        currentPaperPath.finalPoint,
                        executionSegment.targetPosition
                    )
                ) {
                    handleExecutorFailure(ExecutorFailureReason.PAPER_ENDPOINT_MISMATCH)
                    return
                }
            }

            val currentPathPoints = currentPaperPath?.points ?: executionSegment.acceptedPathPoints
            if (!isNearAcceptedPaperPath(currentLocation, currentPathPoints)) {
                handleExecutorFailure(ExecutorFailureReason.ROUTE_DEVIATION)
                return
            }

            val nextPointIndex = currentPaperPath?.nextPointIndex ?: executionSegment.lastNextPointIndex
            val movedDistanceSquared = distanceSquared(currentLocation, executionSegment.lastProgressLocation)
            if (nextPointIndex > executionSegment.lastNextPointIndex ||
                movedDistanceSquared >= MINIMUM_PROGRESS_DISTANCE_SQUARED
            ) {
                executionSegment.lastNextPointIndex = max(executionSegment.lastNextPointIndex, nextPointIndex)
                executionSegment.lastProgressLocation = currentLocation
                executionSegment.lastProgressTime = currentTime
            }

            if (!entity.pathfinder.hasPath()) {
                if (executionSegment.inactiveSince == 0L) {
                    executionSegment.inactiveSince = currentTime
                    return
                }
                if (currentTime - executionSegment.inactiveSince < EXECUTOR_INACTIVE_GRACE_NANOSECONDS) return

                synchronizeLocalProgress(activeLocalRoute, executionSegment.targetLocalIndex)
                if (activeLocalRoute.reachedIndex >= executionSegment.targetLocalIndex) {
                    entity.pathfinder.stopPathfinding()
                    activeExecutionSegment = null
                    executorFailureCount = 0
                    if (activeLocalRoute.reachedIndex >= activeLocalRoute.nodes.lastIndex) {
                        completeLocalRoute(activeLocalRoute)
                    } else {
                        phase = Phase.READY_TO_EXECUTE
                    }
                } else if (isDescendingTowardExecutionTarget(
                        currentLocation,
                        executionSegment,
                        currentTime
                    )
                ) {
                    return
                } else {
                    handleExecutorFailure(ExecutorFailureReason.ENDED_EARLY)
                }
                return
            }

            executionSegment.inactiveSince = 0L
            if (currentTime - executionSegment.lastProgressTime >= STUCK_TIMEOUT_NANOSECONDS) {
                handleStuck()
            }
        }

        private fun synchronizeLocalProgress(
            activeLocalRoute: LocalRoute,
            maximumIndex: Int
        ) {
            if (activeLocalRoute.nodes.isEmpty()) return
            val currentLocation = entity.location
            val upperBound = min(maximumIndex, activeLocalRoute.nodes.lastIndex)
            var reachedIndex = activeLocalRoute.reachedIndex

            for (index in upperBound downTo activeLocalRoute.reachedIndex + 1) {
                val position = AreaManager.Position(activeLocalRoute.nodes[index])
                if (!isEntityAtRoutePosition(currentLocation, position)) continue
                reachedIndex = index
                break
            }

            if (reachedIndex <= activeLocalRoute.reachedIndex) return
            val previousReachedIndex = activeLocalRoute.reachedIndex
            activeLocalRoute.reachedIndex = reachedIndex
            executorFailureCount = 0

            for (index in max(1, previousReachedIndex + 1)..reachedIndex) {
                val transition = LocalTransition(
                    activeLocalRoute.nodes[index - 1],
                    activeLocalRoute.nodes[index]
                )
                blockedTransitions.remove(transition)
                paperEndpointMismatchCounts.remove(transition)
                liveGeometryFailureCounts.remove(transition)
            }
        }

        private fun completeLocalRoute(activeLocalRoute: LocalRoute) {
            val activeMacroRoute = macroRoute ?: run {
                localRoute = null
                phase = Phase.NEED_MACRO
                return
            }
            if (localRoute !== activeLocalRoute) return
            if (activeLocalRoute.reachedIndex < activeLocalRoute.nodes.lastIndex) return

            val expectedObjectiveIndex = if (activeMacroRoute.reachedIndex >= activeMacroRoute.nodes.lastIndex) {
                activeMacroRoute.nodes.lastIndex
            } else {
                activeMacroRoute.reachedIndex + 1
            }
            if (activeLocalRoute.macroObjectiveIndex != expectedObjectiveIndex) {
                invalidateLocalRoute()
                return
            }

            localRoute = null
            activeExecutionSegment = null
            activeMacroRoute.reachedIndex = max(
                activeMacroRoute.reachedIndex,
                activeLocalRoute.macroObjectiveIndex
            )
            localFailureCount = 0
            executorFailureCount = 0
            blockedTransitions.clear()
            paperEndpointMismatchCounts.clear()
            liveGeometryFailureCounts.clear()

            if (isLatestTargetReached()) {
                enterArrivedState()
                return
            }
            phase = Phase.NEED_LOCAL
        }

        private fun determineMaximumExecutionTargetIndex(
            activeLocalRoute: LocalRoute,
            firstTargetIndex: Int,
            lookaheadNodes: Int
        ): Int {
            if (firstTargetIndex >= activeLocalRoute.nodes.lastIndex) return firstTargetIndex
            if (firstTargetIndex == 0) return 0

            val source = AreaManager.Position(activeLocalRoute.nodes[firstTargetIndex - 1])
            val firstTarget = AreaManager.Position(activeLocalRoute.nodes[firstTargetIndex])
            val directionX = firstTarget.x - source.x
            val directionY = firstTarget.y - source.y
            val directionZ = firstTarget.z - source.z
            if (directionY != 0) return firstTargetIndex

            val preferredMaximum = min(
                firstTargetIndex + lookaheadNodes - 1,
                activeLocalRoute.nodes.lastIndex
            )
            var maximumIndex = firstTargetIndex
            var previous = firstTarget

            for (candidateIndex in firstTargetIndex + 1..preferredMaximum) {
                val candidate = AreaManager.Position(activeLocalRoute.nodes[candidateIndex])
                if (candidate.y != previous.y ||
                    candidate.x - previous.x != directionX ||
                    candidate.z - previous.z != directionZ
                ) break

                maximumIndex = candidateIndex
                previous = candidate
            }

            return maximumIndex
        }

        private fun resolveExecutionTargetLocation(
            candidatePosition: AreaManager.Position
        ): Location? {
            val floorY = resolveLiveFloorY(candidatePosition) ?: return null
            return Location(
                entity.world,
                candidatePosition.x + BLOCK_CENTER_OFFSET,
                floorY,
                candidatePosition.z + BLOCK_CENTER_OFFSET
            )
        }

        private fun isPaperFinalPointCompatible(
            finalPoint: Location?,
            targetPosition: AreaManager.Position
        ): Boolean {
            finalPoint ?: return false
            if (finalPoint.world != entity.world) return false
            if (finalPoint.blockX != targetPosition.x || finalPoint.blockZ != targetPosition.z) return false

            return resolveExpectedLiveLogicalPosition(
                finalPoint,
                targetPosition
            )?.raw == targetPosition.raw
        }

        private fun resolveExpectedLiveLogicalPosition(
            location: Location,
            expectedPosition: AreaManager.Position
        ): AreaManager.Position? {
            if (location.world != entity.world) return null
            if (location.blockX != expectedPosition.x || location.blockZ != expectedPosition.z) return null

            val floorY = resolveLiveFloorY(expectedPosition) ?: return null
            if (abs(location.y - floorY) > LOGICAL_POSITION_VERTICAL_DISTANCE) return null

            return expectedPosition
        }

        private fun isDescendingTowardExecutionTarget(
            currentLocation: Location,
            executionSegment: ActiveExecutionSegment,
            currentTime: Long
        ): Boolean {
            if (executionSegment.inactiveSince == 0L) return false
            if (currentTime - executionSegment.inactiveSince >= EXECUTOR_LANDING_GRACE_NANOSECONDS) return false
            if (currentAnchorResolution !is AnchorResolution.Airborne) return false
            if (!footprintOverlapsPosition(
                    currentLocation.x,
                    currentLocation.z,
                    mobWidth * 0.5,
                    executionSegment.targetPosition
                )
            ) return false

            val targetFloorY = executionSegment.physicalTargetLocation.y
            if (currentLocation.y <= targetFloorY + NODE_OCCUPANCY_VERTICAL_TOLERANCE) return false
            return entity.velocity.y < -MINIMUM_DESCENDING_VELOCITY
        }

        private fun isPaperPathBounded(
            path: Pathfinder.PathResult,
            activeLocalRoute: LocalRoute,
            sourceIndex: Int,
            targetIndex: Int
        ): Boolean {
            val points = path.points
            if (points.isEmpty()) return false

            val maximumPathLength = calculateMaximumExecutorPathLength(
                activeLocalRoute,
                sourceIndex,
                targetIndex
            )
            val corridorPoints = ArrayList<Location>(targetIndex - sourceIndex + 2)
            corridorPoints.add(entity.location.clone())
            val firstRouteIndex = max(0, sourceIndex + 1)
            for (routeIndex in firstRouteIndex..targetIndex) {
                val position = AreaManager.Position(activeLocalRoute.nodes[routeIndex])
                val waypoint = resolveExecutionTargetLocation(position) ?: return false
                corridorPoints.add(waypoint)
            }

            val area = hierarchicalGrid.area
            var accumulatedLength = 0.0
            var previousPoint = entity.location

            for (point in points) {
                if (point.world != entity.world) return false
                if (point.blockX !in area.boundingBoxStart.x..area.boundingBoxEnd.x ||
                    point.blockZ !in area.boundingBoxStart.z..area.boundingBoxEnd.z ||
                    point.blockY !in area.boundingBoxStart.y - 1..area.boundingBoxEnd.y + 1
                ) return false

                val chunkX = point.blockX shr Constants.CHUNK_SHIFT
                val chunkZ = point.blockZ shr Constants.CHUNK_SHIFT
                if (!entity.world.isChunkLoaded(chunkX, chunkZ)) return false
                if (!isNearRouteCorridor(point, corridorPoints)) return false

                accumulatedLength += distance(previousPoint, point)
                if (accumulatedLength > maximumPathLength) return false
                previousPoint = point
            }

            return true
        }

        private fun calculateMaximumExecutorPathLength(
            activeLocalRoute: LocalRoute,
            sourceIndex: Int,
            targetIndex: Int
        ): Double {
            var plannedLength = 0.0
            var previous = entity.location
            val firstRouteIndex = max(0, sourceIndex + 1)

            for (index in firstRouteIndex..targetIndex) {
                val position = AreaManager.Position(activeLocalRoute.nodes[index])
                val waypoint = resolveExecutionTargetLocation(position) ?: continue
                plannedLength += distance(previous, waypoint)
                previous = waypoint
            }

            return max(
                EXECUTOR_MINIMUM_MAX_PATH_LENGTH,
                plannedLength * EXECUTOR_PATH_LENGTH_FACTOR + EXECUTOR_PATH_LENGTH_ALLOWANCE
            )
        }

        private fun isNearRouteCorridor(point: Location, corridorPoints: List<Location>): Boolean {
            if (corridorPoints.size == 1) {
                return distanceSquared(point, corridorPoints[0]) <= EXECUTOR_CORRIDOR_DISTANCE_SQUARED
            }

            for (index in 0 until corridorPoints.lastIndex) {
                if (distanceSquaredToSegment(
                        point,
                        corridorPoints[index],
                        corridorPoints[index + 1]
                    ) <= EXECUTOR_CORRIDOR_DISTANCE_SQUARED
                ) return true
            }

            return false
        }

        private fun distanceSquaredToSegment(point: Location, start: Location, end: Location): Double {
            val segmentX = end.x - start.x
            val segmentY = end.y - start.y
            val segmentZ = end.z - start.z
            val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ
            if (segmentLengthSquared <= COST_EPSILON) return distanceSquared(point, start)

            val pointX = point.x - start.x
            val pointY = point.y - start.y
            val pointZ = point.z - start.z
            val ratio = ((pointX * segmentX + pointY * segmentY + pointZ * segmentZ) / segmentLengthSquared)
                .coerceIn(0.0, 1.0)
            val closestX = start.x + segmentX * ratio
            val closestY = start.y + segmentY * ratio
            val closestZ = start.z + segmentZ * ratio
            val deltaX = point.x - closestX
            val deltaY = point.y - closestY
            val deltaZ = point.z - closestZ
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
        }

        private fun isNearAcceptedPaperPath(
            currentLocation: Location,
            acceptedPathPoints: List<Location>
        ): Boolean {
            if (acceptedPathPoints.isEmpty()) return true

            for (point in acceptedPathPoints) {
                if (point.world != currentLocation.world) continue
                if (distanceSquared(currentLocation, point) <= EXECUTOR_DEVIATION_DISTANCE_SQUARED) return true
            }

            return false
        }

        private fun currentGroundedAnchor(): NavigationAnchor? =
            (currentAnchorResolution as? AnchorResolution.Grounded)?.anchor

        private fun refreshCurrentEntityAnchor(): AnchorResolution {
            val resolution = resolvePlannerAnchor(
                entity.location,
                preferredPosition = null
            )
            currentAnchorResolution = resolution
            return resolution
        }

        private fun refreshStableTargetAnchor(targetLocation: Location) {
            val previousAnchor = stableTargetAnchor
            val resolution = resolvePlannerAnchor(
                targetLocation,
                preferredPosition = previousAnchor?.position
            )

            when (resolution) {
                is AnchorResolution.Grounded -> {
                    targetAnchorMissingSince = 0L
                    val newAnchor = resolution.anchor
                    stableTargetAnchor = newAnchor

                    if (previousAnchor?.position?.raw == newAnchor.position.raw) return

                    onStableTargetAnchorChanged(previousAnchor, newAnchor)
                }

                AnchorResolution.Airborne,
                AnchorResolution.Unavailable,
                AnchorResolution.OutsideArea -> {
                    if (previousAnchor == null) return

                    val now = System.nanoTime()
                    if (targetAnchorMissingSince == 0L) {
                        targetAnchorMissingSince = now
                        return
                    }
                    if (now - targetAnchorMissingSince < TARGET_ANCHOR_CONTINUITY_NANOSECONDS) return

                    stableTargetAnchor = null
                    invalidateMacroRoute(clearTransitionEvidence = true)
                }
            }
        }

        private fun onStableTargetAnchorChanged(
            previousAnchor: NavigationAnchor?,
            newAnchor: NavigationAnchor
        ) {
            if (previousAnchor == null) {
                if (phase == Phase.IDLE || phase == Phase.ARRIVED) {
                    phase = if (macroRoute == null) Phase.NEED_MACRO else Phase.NEED_LOCAL
                }
                return
            }

            val targetChunkChanged = previousAnchor.position.chunkX != newAnchor.position.chunkX ||
                    previousAnchor.position.chunkZ != newAnchor.position.chunkZ
            val activeMacroRoute = macroRoute
            if ((activeMacroRoute != null &&
                    !isTargetInMacroChunk(activeMacroRoute, newAnchor.position)) ||
                (phase == Phase.PLANNING_MACRO && targetChunkChanged)
            ) {
                invalidateMacroRoute(clearTransitionEvidence = true)
                return
            }

            if (phase == Phase.ARRIVED) {
                phase = if (activeMacroRoute == null) Phase.NEED_MACRO else Phase.NEED_LOCAL
            }
        }

        private fun resolvePlannerAnchor(
            location: Location,
            preferredPosition: AreaManager.Position?
        ): AnchorResolution {
            if (location.world != entity.world ||
                !location.x.isFinite() ||
                !location.y.isFinite() ||
                !location.z.isFinite()
            ) return AnchorResolution.OutsideArea

            val area = hierarchicalGrid.area
            val halfWidth = mobWidth * 0.5
            val minimumX = location.x - halfWidth
            val maximumX = location.x + halfWidth
            val minimumZ = location.z - halfWidth
            val maximumZ = location.z + halfWidth
            val areaMinimumX = area.boundingBoxStart.x.toDouble()
            val areaMaximumX = area.boundingBoxEnd.x + 1.0
            val areaMinimumZ = area.boundingBoxStart.z.toDouble()
            val areaMaximumZ = area.boundingBoxEnd.z + 1.0

            if (maximumX <= areaMinimumX + SURFACE_EPSILON ||
                minimumX >= areaMaximumX - SURFACE_EPSILON ||
                maximumZ <= areaMinimumZ + SURFACE_EPSILON ||
                minimumZ >= areaMaximumZ - SURFACE_EPSILON
            ) return AnchorResolution.OutsideArea

            val verticalMargin = max(
                hierarchicalGrid.mobPathfindingProfile.maxStepUp,
                hierarchicalGrid.mobPathfindingProfile.maxStepDown
            ) + mobHeight + 2.0
            if (location.y < area.boundingBoxStart.y - verticalMargin ||
                location.y > area.boundingBoxEnd.y + verticalMargin
            ) return AnchorResolution.OutsideArea

            val minimumBlockX = floor(minimumX + SURFACE_EPSILON).toInt()
            val maximumBlockX = floor(maximumX - SURFACE_EPSILON).toInt()
            val minimumBlockZ = floor(minimumZ + SURFACE_EPSILON).toInt()
            val maximumBlockZ = floor(maximumZ - SURFACE_EPSILON).toInt()
            val feetBlockY = floor(location.y).toInt()
            val footprintArea = (maximumX - minimumX) * (maximumZ - minimumZ)
            var unavailableGeometry = false
            var bestCandidate: SupportCandidate? = null

            fun evaluateColumn(blockX: Int, blockZ: Int, requireFootprintOverlap: Boolean) {
                if (!entity.world.isChunkLoaded(
                        blockX shr Constants.CHUNK_SHIFT,
                        blockZ shr Constants.CHUNK_SHIFT
                    )
                ) {
                    unavailableGeometry = true
                    return
                }

                val overlapX = min(maximumX, blockX + 1.0) - max(minimumX, blockX.toDouble())
                val overlapZ = min(maximumZ, blockZ + 1.0) - max(minimumZ, blockZ.toDouble())
                val overlapsFootprint = overlapX > SURFACE_EPSILON && overlapZ > SURFACE_EPSILON
                if (requireFootprintOverlap && !overlapsFootprint) return
                val overlapArea = if (overlapsFootprint) overlapX * overlapZ else 0.0
                val centerDeltaX = location.x - (blockX + BLOCK_CENTER_OFFSET)
                val centerDeltaZ = location.z - (blockZ + BLOCK_CENTER_OFFSET)
                val horizontalDistanceSquared = centerDeltaX * centerDeltaX + centerDeltaZ * centerDeltaZ

                for (logicalY in feetBlockY - 1..feetBlockY + 1) {
                    val position = AreaManager.Position(blockX, logicalY, blockZ)
                    if (position !in area) continue
                    val floorY = resolveLiveFloorY(position) ?: continue
                    val verticalDistance = location.y - floorY
                    if (verticalDistance < -SURFACE_EPSILON ||
                        verticalDistance > ANCHOR_SUPPORT_VERTICAL_TOLERANCE
                    ) continue

                    val candidate = SupportCandidate(
                        anchor = NavigationAnchor(position, floorY),
                        verticalGap = verticalDistance,
                        containsCenter = location.blockX == blockX && location.blockZ == blockZ,
                        horizontalDistanceSquared = horizontalDistanceSquared,
                        overlapArea = overlapArea,
                        preferred = preferredPosition?.raw == position.raw &&
                                overlapArea >= footprintArea * ANCHOR_PREFERRED_MIN_OVERLAP_RATIO
                    )
                    if (bestCandidate == null || isBetterSupportCandidate(candidate, bestCandidate!!)) {
                        bestCandidate = candidate
                    }
                }
            }

            for (blockX in minimumBlockX..maximumBlockX) {
                for (blockZ in minimumBlockZ..maximumBlockZ) {
                    evaluateColumn(blockX, blockZ, requireFootprintOverlap = true)
                }
            }

            if (bestCandidate == null) {
                val centerBlockX = location.blockX
                val centerBlockZ = location.blockZ
                for (blockX in centerBlockX - 1..centerBlockX + 1) {
                    for (blockZ in centerBlockZ - 1..centerBlockZ + 1) {
                        evaluateColumn(blockX, blockZ, requireFootprintOverlap = false)
                    }
                }
            }

            return bestCandidate?.let { AnchorResolution.Grounded(it.anchor) }
                ?: if (unavailableGeometry) AnchorResolution.Unavailable else AnchorResolution.Airborne
        }

        private fun isBetterSupportCandidate(
            candidate: SupportCandidate,
            currentBest: SupportCandidate
        ): Boolean {
            if (candidate.verticalGap < currentBest.verticalGap - SURFACE_EPSILON) return true
            if (candidate.verticalGap > currentBest.verticalGap + SURFACE_EPSILON) return false
            if (candidate.preferred != currentBest.preferred) return candidate.preferred
            if (candidate.containsCenter != currentBest.containsCenter) return candidate.containsCenter
            if (candidate.horizontalDistanceSquared < currentBest.horizontalDistanceSquared - SURFACE_EPSILON) return true
            if (candidate.horizontalDistanceSquared > currentBest.horizontalDistanceSquared + SURFACE_EPSILON) return false
            if (candidate.overlapArea > currentBest.overlapArea + SURFACE_EPSILON) return true
            if (candidate.overlapArea < currentBest.overlapArea - SURFACE_EPSILON) return false
            return candidate.anchor.position.raw < currentBest.anchor.position.raw
        }

        private fun footprintOverlapsPosition(
            centerX: Double,
            centerZ: Double,
            halfWidth: Double,
            position: AreaManager.Position
        ): Boolean {
            val minimumX = centerX - halfWidth
            val maximumX = centerX + halfWidth
            val minimumZ = centerZ - halfWidth
            val maximumZ = centerZ + halfWidth
            val overlapX = min(maximumX, position.x + 1.0) - max(minimumX, position.x.toDouble())
            val overlapZ = min(maximumZ, position.z + 1.0) - max(minimumZ, position.z.toDouble())
            return overlapX > SURFACE_EPSILON && overlapZ > SURFACE_EPSILON
        }

        private fun isEntityAtRoutePosition(
            location: Location,
            position: AreaManager.Position
        ): Boolean {
            if (location.world != entity.world ||
                location.blockX != position.x ||
                location.blockZ != position.z
            ) return false

            val floorY = resolveLiveFloorY(position) ?: return false
            val verticalDistance = location.y - floorY
            return verticalDistance >= -SURFACE_EPSILON &&
                    verticalDistance <= NODE_OCCUPANCY_VERTICAL_TOLERANCE
        }

        private fun resolveLiveFloorY(position: AreaManager.Position): Double? {
            val nominalY = position.y.toDouble()
            var floorY = findLiveSupportSurface(
                position.x,
                position.y - 1,
                position.z,
                nominalY - 1.0 - SURFACE_EPSILON,
                nominalY + SURFACE_EPSILON,
                Double.POSITIVE_INFINITY,
                0.0
            ) ?: return null

            val requiredBodySurfaceHalfWidth = min(
                mobWidth * 0.5,
                BLOCK_CENTER_OFFSET - SURFACE_EPSILON
            )
            val bodySurface = findLiveSupportSurface(
                position.x,
                position.y,
                position.z,
                nominalY - SURFACE_EPSILON,
                nominalY + BODY_SURFACE_MAXIMUM_RISE + SURFACE_EPSILON,
                nominalY + BODY_SURFACE_BASE_TOLERANCE,
                requiredBodySurfaceHalfWidth
            )
            if (bodySurface != null && bodySurface > floorY) {
                floorY = bodySurface
            }

            if (!isLiveBodyClearAt(
                    position.x + BLOCK_CENTER_OFFSET,
                    position.z + BLOCK_CENTER_OFFSET,
                    floorY
                )
            ) return null
            return floorY
        }

        private fun findLiveSupportSurface(
            blockX: Int,
            blockY: Int,
            blockZ: Int,
            minimumSurfaceY: Double,
            maximumSurfaceY: Double,
            maximumBaseY: Double,
            minimumHorizontalHalfWidth: Double
        ): Double? {
            if (blockY !in entity.world.minHeight..< entity.world.maxHeight) return null
            if (!entity.world.isChunkLoaded(
                    blockX shr Constants.CHUNK_SHIFT,
                    blockZ shr Constants.CHUNK_SHIFT
                )
            ) return null

            val block = entity.world.getBlockAt(blockX, blockY, blockZ)
            val minimumHorizontalCoordinate = BLOCK_CENTER_OFFSET - minimumHorizontalHalfWidth
            val maximumHorizontalCoordinate = BLOCK_CENTER_OFFSET + minimumHorizontalHalfWidth
            var highestSurface: Double? = null

            for (boundingBox in block.collisionShape.boundingBoxes) {
                if (minimumHorizontalCoordinate < boundingBox.minX - SURFACE_EPSILON ||
                    maximumHorizontalCoordinate > boundingBox.maxX + SURFACE_EPSILON ||
                    minimumHorizontalCoordinate < boundingBox.minZ - SURFACE_EPSILON ||
                    maximumHorizontalCoordinate > boundingBox.maxZ + SURFACE_EPSILON
                ) continue

                val baseY = blockY + boundingBox.minY
                if (baseY > maximumBaseY + SURFACE_EPSILON) continue

                val surfaceY = blockY + boundingBox.maxY
                if (surfaceY !in minimumSurfaceY..maximumSurfaceY) continue
                if (highestSurface == null || surfaceY > highestSurface) highestSurface = surfaceY
            }

            return highestSurface
        }

        private fun isLiveBodyClearAt(
            centerX: Double,
            centerZ: Double,
            floorY: Double
        ): Boolean {
            val halfWidth = mobWidth * 0.5
            val minimumX = centerX - halfWidth
            val maximumX = centerX + halfWidth
            val minimumZ = centerZ - halfWidth
            val maximumZ = centerZ + halfWidth
            val minimumY = floorY + SURFACE_EPSILON
            val maximumY = floorY + mobHeight

            val minimumBlockX = floor(minimumX + SURFACE_EPSILON).toInt()
            val maximumBlockX = floor(maximumX - SURFACE_EPSILON).toInt()
            val minimumBlockZ = floor(minimumZ + SURFACE_EPSILON).toInt()
            val maximumBlockZ = floor(maximumZ - SURFACE_EPSILON).toInt()
            val minimumBlockY = floor(minimumY).toInt()
            val maximumBlockY = floor(maximumY - SURFACE_EPSILON).toInt()

            if (minimumBlockY !in entity.world.minHeight..< entity.world.maxHeight ||
                maximumBlockY !in entity.world.minHeight..< entity.world.maxHeight
            ) return false

            for (blockX in minimumBlockX..maximumBlockX) {
                for (blockZ in minimumBlockZ..maximumBlockZ) {
                    if (!entity.world.isChunkLoaded(
                            blockX shr Constants.CHUNK_SHIFT,
                            blockZ shr Constants.CHUNK_SHIFT
                        )
                    ) return false

                    for (blockY in minimumBlockY..maximumBlockY) {
                        val block = entity.world.getBlockAt(blockX, blockY, blockZ)
                        for (box in block.collisionShape.boundingBoxes) {
                            val boxMinimumX = blockX + box.minX
                            val boxMaximumX = blockX + box.maxX
                            val boxMinimumY = blockY + box.minY
                            val boxMaximumY = blockY + box.maxY
                            val boxMinimumZ = blockZ + box.minZ
                            val boxMaximumZ = blockZ + box.maxZ

                            if (boxMaximumX <= minimumX + SURFACE_EPSILON ||
                                boxMinimumX >= maximumX - SURFACE_EPSILON ||
                                boxMaximumY <= minimumY + SURFACE_EPSILON ||
                                boxMinimumY >= maximumY - SURFACE_EPSILON ||
                                boxMaximumZ <= minimumZ + SURFACE_EPSILON ||
                                boxMinimumZ >= maximumZ - SURFACE_EPSILON
                            ) continue
                            return false
                        }
                    }
                }
            }

            return true
        }

        private fun canTraverseLive(
            source: AreaManager.Position,
            target: AreaManager.Position
        ): Boolean {
            val deltaX = target.x - source.x
            val deltaZ = target.z - source.z
            if (deltaX !in -1..1 || deltaZ !in -1..1 || deltaX == 0 && deltaZ == 0) return false

            val sourceFloorY = resolveLiveFloorY(source) ?: return false
            val targetFloorY = resolveLiveFloorY(target) ?: return false
            val floorDelta = targetFloorY - sourceFloorY
            val profile = hierarchicalGrid.mobPathfindingProfile
            if (floorDelta > profile.maxStepUp + SURFACE_EPSILON) return false
            if (floorDelta < -profile.maxStepDown - SURFACE_EPSILON) return false

            val travelFloorY = max(sourceFloorY, targetFloorY)
            val sourceCenterX = source.x + BLOCK_CENTER_OFFSET
            val sourceCenterZ = source.z + BLOCK_CENTER_OFFSET
            val targetCenterX = target.x + BLOCK_CENTER_OFFSET
            val targetCenterZ = target.z + BLOCK_CENTER_OFFSET
            val midpointX = (sourceCenterX + targetCenterX) * 0.5
            val midpointZ = (sourceCenterZ + targetCenterZ) * 0.5

            if (floorDelta > SURFACE_EPSILON &&
                !isLiveBodyClearAt(sourceCenterX, sourceCenterZ, travelFloorY)
            ) return false
            if (!isLiveBodyClearAt(midpointX, midpointZ, travelFloorY)) return false
            if (floorDelta < -SURFACE_EPSILON &&
                !isLiveBodyClearAt(targetCenterX, targetCenterZ, travelFloorY)
            ) return false

            if (deltaX != 0 && deltaZ != 0) {
                if (!isLiveBodyClearAt(
                        sourceCenterX + deltaX * 0.5,
                        sourceCenterZ,
                        travelFloorY
                    )
                ) return false
                if (!isLiveBodyClearAt(
                        sourceCenterX,
                        sourceCenterZ + deltaZ * 0.5,
                        travelFloorY
                    )
                ) return false
            }

            return true
        }

        private fun findInvalidLiveTransition(
            activeLocalRoute: LocalRoute,
            targetIndex: Int
        ): LocalTransition? {
            val firstTargetIndex = max(1, activeLocalRoute.reachedIndex + 1)
            if (firstTargetIndex > targetIndex) return null

            for (index in firstTargetIndex..targetIndex) {
                val sourceRaw = activeLocalRoute.nodes[index - 1]
                val targetRaw = activeLocalRoute.nodes[index]
                val transition = LocalTransition(sourceRaw, targetRaw)
                if (transition in blockedTransitions) return transition
                if (!canTraverseLive(
                        AreaManager.Position(sourceRaw),
                        AreaManager.Position(targetRaw)
                    )
                ) return transition
            }

            return null
        }

        private fun isLatestTargetReached(): Boolean {
            val target = desiredTargetLocation ?: return false
            val currentAnchor = currentGroundedAnchor() ?: return false
            val targetAnchor = stableTargetAnchor ?: return false
            if (abs(currentAnchor.floorY - targetAnchor.floorY) > FINAL_REACHED_FLOOR_TOLERANCE) return false

            val currentLocation = entity.location
            val horizontalDeltaX = currentLocation.x - target.x
            val horizontalDeltaZ = currentLocation.z - target.z
            return horizontalDeltaX * horizontalDeltaX + horizontalDeltaZ * horizontalDeltaZ <=
                    finalReachedHorizontalDistanceSquared
        }

        private fun createLocalSearchArea(
            source: AreaManager.Position,
            target: AreaManager.Position
        ): AreaManager.Area? {
            if (abs(source.chunkX - target.chunkX) > LOCAL_SEARCH_MAXIMUM_CHUNK_DELTA ||
                abs(source.chunkZ - target.chunkZ) > LOCAL_SEARCH_MAXIMUM_CHUNK_DELTA
            ) return null

            val gridArea = hierarchicalGrid.area
            val minimumChunkX = min(source.chunkX, target.chunkX)
            val maximumChunkX = max(source.chunkX, target.chunkX)
            val minimumChunkZ = min(source.chunkZ, target.chunkZ)
            val maximumChunkZ = max(source.chunkZ, target.chunkZ)
            val minimumX = max(gridArea.boundingBoxStart.x, minimumChunkX shl Constants.CHUNK_SHIFT)
            val maximumX = min(gridArea.boundingBoxEnd.x, ((maximumChunkX + 1) shl Constants.CHUNK_SHIFT) - 1)
            val minimumZ = max(gridArea.boundingBoxStart.z, minimumChunkZ shl Constants.CHUNK_SHIFT)
            val maximumZ = min(gridArea.boundingBoxEnd.z, ((maximumChunkZ + 1) shl Constants.CHUNK_SHIFT) - 1)

            return AreaManager.Area(
                AreaManager.Position(minimumX, gridArea.boundingBoxStart.y, minimumZ),
                AreaManager.Position(maximumX, gridArea.boundingBoxEnd.y, maximumZ)
            )
        }

        private fun handleHierarchicalRouteUnavailable() {
            if (!blockedEntranceIds.isEmpty()) {
                blockedEntranceIds.clear()
            }
            invalidateMacroRoute(clearTransitionEvidence = false)
            scheduleRetry(MACRO_RETRY_DELAY_MILLISECONDS, Phase.NEED_MACRO)
        }

        private fun handleLocalRouteUnavailable() {
            localFailureCount++
            localRoute = null
            activeExecutionSegment = null
            entity.pathfinder.stopPathfinding()

            if (localFailureCount >= LOCAL_FAILURES_BEFORE_MACRO_REPLAN) {
                if (blockedTransitions.isNotEmpty()) {
                    blockedTransitions.clear()
                    paperEndpointMismatchCounts.clear()
                    liveGeometryFailureCounts.clear()
                    localFailureCount = 0
                    scheduleRetry(LOCAL_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
                    return
                }

                rememberBlockedMacroObjective()
                localFailureCount = 0
                invalidateMacroRoute(clearTransitionEvidence = false)
                scheduleRetry(MACRO_RETRY_DELAY_MILLISECONDS, Phase.NEED_MACRO)
            } else {
                scheduleRetry(LOCAL_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
            }
        }

        private fun handleExecutorFailure(reason: ExecutorFailureReason) {
            entity.pathfinder.stopPathfinding()
            activeExecutionSegment = null

            when (reason) {
                ExecutorFailureReason.LIVE_GEOMETRY_INVALID -> {
                    immediateTransition()?.let(::recordLiveGeometryFailure)
                    executorFailureCount = 0
                    invalidateLocalRoute()
                    scheduleRetry(LOCAL_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
                }

                ExecutorFailureReason.ROUTE_DEVIATION -> {
                    executorFailureCount = 0
                    invalidateLocalRoute()
                    scheduleRetry(LOCAL_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
                }

                ExecutorFailureReason.PAPER_ENDPOINT_MISMATCH -> {
                    executorFailureCount = 0
                    if (recordPaperEndpointMismatch()) {
                        invalidateLocalRoute()
                        scheduleRetry(LOCAL_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
                    } else {
                        scheduleRetry(EXECUTOR_RETRY_DELAY_MILLISECONDS, Phase.READY_TO_EXECUTE)
                    }
                }

                ExecutorFailureReason.PATH_UNAVAILABLE,
                ExecutorFailureReason.PARTIAL_PATH,
                ExecutorFailureReason.PAPER_ROUTE_OUTSIDE_CORRIDOR,
                ExecutorFailureReason.START_REJECTED,
                ExecutorFailureReason.ENDED_EARLY -> {
                    executorFailureCount++
                    if (executorFailureCount <= EXECUTOR_RETRIES_BEFORE_LOCAL_REPLAN && localRoute != null) {
                        scheduleRetry(EXECUTOR_RETRY_DELAY_MILLISECONDS, Phase.READY_TO_EXECUTE)
                    } else {
                        executorFailureCount = 0
                        invalidateLocalRoute()
                        scheduleRetry(LOCAL_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
                    }
                }
            }
        }

        private fun handleStuck() {
            entity.pathfinder.stopPathfinding()
            activeExecutionSegment = null
            executorFailureCount++

            if (executorFailureCount <= EXECUTOR_RETRIES_BEFORE_LOCAL_REPLAN && localRoute != null) {
                scheduleRetry(EXECUTOR_RETRY_DELAY_MILLISECONDS, Phase.READY_TO_EXECUTE)
            } else {
                executorFailureCount = 0
                invalidateLocalRoute()
                scheduleRetry(LOCAL_RETRY_DELAY_MILLISECONDS, Phase.NEED_LOCAL)
            }
        }

        private fun immediateTransition(activeLocalRoute: LocalRoute): LocalTransition? {
            val nextIndex = activeLocalRoute.reachedIndex + 1
            if (nextIndex <= 0 || nextIndex !in activeLocalRoute.nodes.indices) return null
            return LocalTransition(
                activeLocalRoute.nodes[nextIndex - 1],
                activeLocalRoute.nodes[nextIndex]
            )
        }

        private fun immediateTransition(): LocalTransition? {
            val activeLocalRoute = localRoute ?: return null
            return immediateTransition(activeLocalRoute)
        }

        private fun recordPaperEndpointMismatch(): Boolean {
            val transition = immediateTransition() ?: return false
            val mismatchCount = (paperEndpointMismatchCounts[transition] ?: 0) + 1

            if (mismatchCount < PAPER_ENDPOINT_MISMATCHES_BEFORE_TRANSITION_BLOCK) {
                paperEndpointMismatchCounts[transition] = mismatchCount
                return false
            }

            paperEndpointMismatchCounts.remove(transition)
            blockedTransitions.add(transition)
            return true
        }

        private fun recordLiveGeometryFailure(transition: LocalTransition) {
            val failureCount = (liveGeometryFailureCounts[transition] ?: 0) + 1
            if (failureCount < LIVE_GEOMETRY_FAILURES_BEFORE_TRANSITION_BLOCK) {
                liveGeometryFailureCounts[transition] = failureCount
                return
            }

            liveGeometryFailureCounts.remove(transition)
            paperEndpointMismatchCounts.remove(transition)
            blockedTransitions.add(transition)
        }

        private fun rememberBlockedMacroObjective() {
            if (blockedEntranceIds.size >= MAX_BLOCKED_ENTRANCES) return
            val activeMacroRoute = macroRoute ?: return
            val objectiveIndex = activeMacroRoute.reachedIndex + 1
            if (objectiveIndex <= 0 || objectiveIndex >= activeMacroRoute.nodes.lastIndex) return
            blockedEntranceIds.add(activeMacroRoute.nodes[objectiveIndex])
        }

        private fun invalidateMacroRoute(clearTransitionEvidence: Boolean) {
            navigationEpoch++
            planningJob?.cancel()
            planningJob = null
            macroRoute = null
            localRoute = null
            activeExecutionSegment = null
            entity.pathfinder.stopPathfinding()
            executorFailureCount = 0
            localFailureCount = 0
            if (clearTransitionEvidence) {
                blockedTransitions.clear()
                paperEndpointMismatchCounts.clear()
                liveGeometryFailureCounts.clear()
                blockedEntranceIds.clear()
            }
            phase = if (desiredTargetLocation == null) Phase.IDLE else Phase.NEED_MACRO
        }

        private fun invalidateLocalRoute() {
            navigationEpoch++
            planningJob?.cancel()
            planningJob = null
            localRoute = null
            activeExecutionSegment = null
            entity.pathfinder.stopPathfinding()
            executorFailureCount = 0
            phase = if (macroRoute == null) Phase.NEED_MACRO else Phase.NEED_LOCAL
        }

        private fun enterArrivedState() {
            if (phase == Phase.ARRIVED && activeExecutionSegment == null && planningJob == null) return
            navigationEpoch++
            planningJob?.cancel()
            planningJob = null
            localRoute = null
            activeExecutionSegment = null
            entity.pathfinder.stopPathfinding()
            executorFailureCount = 0
            localFailureCount = 0
            blockedTransitions.clear()
            paperEndpointMismatchCounts.clear()
            liveGeometryFailureCounts.clear()
            blockedEntranceIds.clear()
            phase = Phase.ARRIVED
        }

        private fun scheduleRetry(delayMilliseconds: Long, resumePhase: Phase) {
            retryAt = System.nanoTime() + delayMilliseconds * 1_000_000L
            retryResumePhase = resumePhase
            phase = Phase.RETRY
        }

        private fun obtainLocalSnapshots(
            currentChunkX: Int,
            currentChunkZ: Int,
            objectiveChunkX: Int,
            objectiveChunkZ: Int
        ): Long2ObjectMap<ChunkSnapshot> {
            val snapshots = Long2ObjectOpenHashMap<ChunkSnapshot>(18)
            snapshots.putAll(
                gridRegistry.captureNeighborSnapshots(entity.world, currentChunkX, currentChunkZ)
            )
            if (currentChunkX != objectiveChunkX || currentChunkZ != objectiveChunkZ) {
                snapshots.putAll(
                    gridRegistry.captureNeighborSnapshots(entity.world, objectiveChunkX, objectiveChunkZ)
                )
            }
            return snapshots
        }

        private fun isTargetInMacroChunk(
            route: MacroRoute,
            targetPosition: AreaManager.Position
        ): Boolean =
            targetPosition.chunkX == route.targetChunkX &&
                    targetPosition.chunkZ == route.targetChunkZ

        private fun isCurrentRequest(requestEpoch: Long): Boolean =
            requestEpoch == navigationEpoch

        private fun isEntityUsable(): Boolean =
            entity.isValid &&
                    !entity.isDead &&
                    hierarchicalGrid.active &&
                    entity.world.uid == worldId

        private fun isTargetUsable(targetLocation: Location): Boolean {
            if (targetLocation.world != entity.world ||
                !targetLocation.x.isFinite() ||
                !targetLocation.y.isFinite() ||
                !targetLocation.z.isFinite()
            ) return false

            if (targetLocation.y < entity.world.minHeight - 2.0 ||
                targetLocation.y > entity.world.maxHeight + 2.0
            ) return false

            val area = hierarchicalGrid.area
            val margin = max(TARGET_AREA_MARGIN, mobWidth)
            return targetLocation.x >= area.boundingBoxStart.x - margin &&
                    targetLocation.x <= area.boundingBoxEnd.x + 1.0 + margin &&
                    targetLocation.z >= area.boundingBoxStart.z - margin &&
                    targetLocation.z <= area.boundingBoxEnd.z + 1.0 + margin
        }

        private fun distance(source: Location, target: Location): Double =
            sqrt(distanceSquared(source, target))

        private fun distanceSquared(source: Location, target: Location): Double {
            val deltaX = source.x - target.x
            val deltaY = source.y - target.y
            val deltaZ = source.z - target.z
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
        }

        private fun stopNavigation() {
            navigationEpoch++
            planningJob?.cancel()
            planningJob = null
            macroRoute = null
            localRoute = null
            activeExecutionSegment = null
            desiredTargetLocation = null
            currentAnchorResolution = AnchorResolution.Airborne
            stableTargetAnchor = null
            targetAnchorMissingSince = 0L
            entity.pathfinder.stopPathfinding()
            executorFailureCount = 0
            localFailureCount = 0
            blockedTransitions.clear()
            paperEndpointMismatchCounts.clear()
            liveGeometryFailureCounts.clear()
            blockedEntranceIds.clear()
            retryAt = 0L
            phase = Phase.IDLE
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
