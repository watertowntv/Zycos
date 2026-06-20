@file:Suppress("unused")

package zaqws.zycos

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.ChunkSnapshot
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Mob
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.plugin.java.JavaPlugin
import zaqws.zycos.CoroutineManager.scope
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.ceil


class PathfindingManager {
    companion object {
        fun getChunkKey(chunkX: Int, chunkZ: Int) =
            (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

        private fun LongArray.calculatePathCost(): Double {
            var cost = 0.0

            for (i in 0 until size - 1) {
                val curr = AreaManager.Position(this[i])
                val next = AreaManager.Position(this[i + 1])

                cost += (if (next.x != curr.x && next.z != curr.z) 1.414 else 1.0) + (if (next.y > curr.y) 0.5 else 0.0)
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
        val area: AreaManager.Area
    ) {
        val hierarchicalLock = ReentrantReadWriteLock()

        val clusters = hashMapOf<Long, Cluster>()
        val entrances = hashMapOf<Long, Entrance>()

        fun getOrCreateCluster(chunkX: Int, chunkZ: Int) =
            clusters.getOrPut(getChunkKey(chunkX, chunkZ)) {
                Cluster(chunkX, chunkZ)
            }
        fun getEntrance(entranceId: Long) = entrances[entranceId]

        fun clearClusterData(chunkX: Int, chunkZ: Int) {
            val chunkKey = getChunkKey(chunkX, chunkZ)
            val cluster = clusters[chunkKey] ?: return

            val entranceIds = cluster.entranceIds
            for (i in entranceIds.indices) {
                entrances.remove(entranceIds[i])
            }
            entranceIds.clear()

            cleanOrphanEdges(chunkX, chunkZ)
        }

        private fun cleanOrphanEdges(targetChunkX: Int, targetChunkZ: Int) {
            for (dx in -1..1)
                for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    val neighborKey = getChunkKey(targetChunkX + dx, targetChunkZ + dz)
                    val neighborCluster = clusters[neighborKey] ?: continue

                    val neighborEntranceIds = neighborCluster.entranceIds
                    val deadEntranceIds = ArrayList<Long>()

                    neighborEntranceIds.fastForEach { neighborEntranceId ->
                        val entrance = entrances[neighborEntranceId] ?: return@fastForEach

                        entrance.interEdges.fastRemoveIf { edge ->
                            isBelongToChunk(edge.targetEntranceId, targetChunkX, targetChunkZ)
                        }
                        entrance.intraEdges.fastRemoveIf { edge ->
                            isBelongToChunk(edge.targetEntranceId, targetChunkX, targetChunkZ)
                        }

                        if (entrance.interEdges.isEmpty()) {
                            deadEntranceIds.add(neighborEntranceId)
                            entrances.remove(neighborEntranceId)
                        }
                    }

                    if (deadEntranceIds.isEmpty()) continue
                    neighborEntranceIds.fastRemoveIf {
                        it in deadEntranceIds
                    }

                    for (index in neighborEntranceIds.indices) {
                        val siblingEntrance = entrances[neighborEntranceIds[index]] ?: continue

                        siblingEntrance.intraEdges.fastRemoveIf {
                            it.targetEntranceId in deadEntranceIds
                        }
                    }
                }
        }

        private fun isBelongToChunk(entranceId: Long, chunkX: Int, chunkZ: Int): Boolean {
            val cX = (entranceId shr 42).toInt()
            val cZ = (entranceId shl 26 shr 42).toInt()

            return cX == chunkX && cZ == chunkZ
        }
    }


    class LocalPathfinder(
        var chunkSnapshots: Long2ObjectMap<ChunkSnapshot>,
        private val limitToCurrentChunk: Boolean = false,
        private val mobHeight: Int = 2,
        private val mobWidth: Double = 0.6
    ) {
        private val deltaXOffsets = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val deltaZOffsets = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)

        private val openSet = PriorityQueue<PathNode>()
        private val accumulatedCostMap = Long2DoubleOpenHashMap().apply {
            defaultReturnValue(Double.MAX_VALUE)
        }
        private val navigationParentMap = Long2LongOpenHashMap().apply {
            defaultReturnValue(-1L)
        }

        private var lastChunkKey = -1L
        private var lastSnapshot: ChunkSnapshot? = null


        private class PathNode(
            val positionRaw: Long,
            val estimatedTotalCost: Double
        ) : Comparable<PathNode> {
            override fun compareTo(other: PathNode): Int =
                estimatedTotalCost.compareTo(other.estimatedTotalCost)
        }

        fun findPath(start: AreaManager.Position, end: AreaManager.Position, area: AreaManager.Area): LongArray {
            if (start.raw == end.raw) return longArrayOf(start.raw)
            if (end !in area) return LongArray(0)

            openSet.clear()
            accumulatedCostMap.clear()
            navigationParentMap.clear()

            lastChunkKey = -1L
            lastSnapshot = null

            accumulatedCostMap[start.raw] = 0.0
            openSet.add(
                PathNode(start.raw, start.distance(end))
            )

            while (openSet.isNotEmpty()) {
                val currentRecord = openSet.poll()
                val currentRaw = currentRecord.positionRaw
                val currentPosition = AreaManager.Position(currentRaw)

                if (currentPosition.x == end.x && currentPosition.y == end.y && currentPosition.z == end.z)
                    return reconstructPath(navigationParentMap, currentRaw)

                val currentAccumulatedCost = accumulatedCostMap.get(currentRaw)
                if (currentRecord.estimatedTotalCost > currentAccumulatedCost + currentPosition.distance(end) + 1e-9) continue

                expandNeighbors(currentPosition, end, area, openSet, accumulatedCostMap, navigationParentMap)
            }

            return LongArray(0)
        }

        private fun expandNeighbors(
            current: AreaManager.Position,
            end: AreaManager.Position,
            area: AreaManager.Area,
            openSet: PriorityQueue<PathNode>,
            accumulatedCostMap: Long2DoubleOpenHashMap,
            navigationParentMap: Long2LongOpenHashMap
        ) {
            val currentAccumulatedCost = accumulatedCostMap.get(current.raw)

            for (directionIndex in 0..7) {
                val deltaX = deltaXOffsets[directionIndex]
                val deltaZ = deltaZOffsets[directionIndex]

                for (deltaY in 1 downTo -3) {
                    val targetPosition = AreaManager.Position(
                        current.x + deltaX,
                        current.y + deltaY,
                        current.z + deltaZ
                    )

                    if (targetPosition !in area) continue
                    if (limitToCurrentChunk && (targetPosition.chunkX != current.chunkX || targetPosition.chunkZ != current.chunkZ)) continue
                    if (!isWalkable(current, targetPosition)) continue
                    if (deltaX != 0 && deltaZ != 0 && isDiagonalBlocked(current, deltaX, deltaZ, targetPosition.y)) continue

                    val edgeCost = if (deltaX != 0 && deltaZ != 0) 1.414 else 1.0
                    val jumpPenalty = if (deltaY > 0) 0.5 else 0.0
                    val tentativeAccumulatedCost = currentAccumulatedCost + edgeCost + jumpPenalty

                    val targetAccumulatedCost = accumulatedCostMap.get(targetPosition.raw)
                    if (tentativeAccumulatedCost >= targetAccumulatedCost) continue

                    navigationParentMap[targetPosition.raw] = current.raw
                    accumulatedCostMap[targetPosition.raw] = tentativeAccumulatedCost

                    openSet.add(
                        PathNode(targetPosition.raw, tentativeAccumulatedCost + targetPosition.distance(end))
                    )
                }
            }
        }

        private fun reconstructPath(navigationParentMap: Long2LongOpenHashMap, endRaw: Long): LongArray {
            val pathList = ArrayList<Long>()

            var current: Long = endRaw
            while (current != -1L) {
                pathList.add(current)
                current = navigationParentMap.get(current)
            }

            val array = LongArray(pathList.size)
            for (index in array.indices) {
                array[index] = pathList[pathList.size - 1 - index]
            }

            return array
        }


        private fun getBlockMaterial(globalX: Int, globalY: Int, globalZ: Int): Material {
            if (globalY < -64 || globalY > 319) return Material.AIR

            val chunkX = globalX shr 4
            val chunkZ = globalZ shr 4

            val chunkKey = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)
            var snapshot = lastSnapshot

            if (chunkKey != lastChunkKey) {
                snapshot = chunkSnapshots.get(chunkKey)
                lastChunkKey = chunkKey
                lastSnapshot = snapshot
            }
            if (snapshot == null) return Material.AIR

            return snapshot.getBlockType(globalX and 15, globalY, globalZ and 15)
        }

        private fun isWalkable(current: AreaManager.Position, target: AreaManager.Position): Boolean {
            val topY = current.y.coerceAtLeast(target.y) + mobHeight - 1
            val radius = mobWidth * 0.5
            val blockRadius = if (radius <= 0.5) 0 else 1

            for (dz in -blockRadius..blockRadius)
                for (dx in -blockRadius..blockRadius)
                    for (y in target.y..topY) {
                        if (getBlockMaterial(target.x + dx, y, target.z + dz).isSolid) return false
                    }

            if (!getBlockMaterial(target.x, target.y - 1, target.z).isSolid) return false

            if (target.y > current.y)
                for (dz in -blockRadius..blockRadius)
                    for (dx in -blockRadius..blockRadius)
                        if (getBlockMaterial(current.x + dx, current.y + mobHeight, current.z + dz).isSolid) return false

            return true
        }

        private fun isDiagonalBlocked(current: AreaManager.Position, deltaX: Int, deltaZ: Int, targetY: Int): Boolean {
            val minHeight = current.y.coerceAtMost(targetY)
            val maxHeight = current.y.coerceAtLeast(targetY) + 1

            for (checkY in minHeight..maxHeight) {
                if (getBlockMaterial(current.x + deltaX, checkY, current.z).isSolid) return true
                if (getBlockMaterial(current.x, checkY, current.z + deltaZ).isSolid) return true
            }

            return false
        }
    }


    class GridRegistry {
        private val gridRegistryMap = ConcurrentHashMap<String, HierarchicalGrid>()

        fun getOrCreateGrid(identifier: String, area: AreaManager.Area): HierarchicalGrid =
            gridRegistryMap.getOrPut(identifier) { HierarchicalGrid(area) }

        fun registerGrid(identifier: String, area: AreaManager.Area): HierarchicalGrid =
            HierarchicalGrid(area).also {
                gridRegistryMap[identifier] = it
            }

        fun removeGrid(identifier: String): HierarchicalGrid? =
            gridRegistryMap.remove(identifier)

        fun clear() {
            gridRegistryMap.clear()
        }


        fun captureAreaSnapshots(world: World, area: AreaManager.Area): Long2ObjectMap<ChunkSnapshot> {
            val snapshots = Long2ObjectOpenHashMap<ChunkSnapshot>()

            val minChunkX = area.boundingBoxStart.chunkX
            val maxChunkX = area.boundingBoxEnd.chunkX
            val minChunkZ = area.boundingBoxStart.chunkZ
            val maxChunkZ = area.boundingBoxEnd.chunkZ

            for (chunkX in minChunkX..maxChunkX)
                for (chunkZ in minChunkZ..maxChunkZ) {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) continue

                    snapshots[getChunkKey(chunkX, chunkZ)] = world.getChunkAt(chunkX, chunkZ).chunkSnapshot
                }

            return snapshots
        }

        fun captureNeighborSnapshots(world: World, centerChunkX: Int, centerChunkZ: Int): Long2ObjectMap<ChunkSnapshot> {
            val snapshots = Long2ObjectOpenHashMap<ChunkSnapshot>()

            for (deltaX in -1..1)
                for (deltaZ in -1..1) {
                    val targetChunkX = centerChunkX + deltaX
                    val targetChunkZ = centerChunkZ + deltaZ

                    if (!world.isChunkLoaded(targetChunkX, targetChunkZ)) continue
                    snapshots[getChunkKey(targetChunkX, targetChunkZ)] = world.getChunkAt(targetChunkX, targetChunkZ).chunkSnapshot
                }

            return snapshots
        }

        suspend fun bakeAll(
            hierarchicalGrid: HierarchicalGrid,
            chunkSnapshots: Long2ObjectMap<ChunkSnapshot>
        ) = withContext(Dispatchers.Default) {
            hierarchicalGrid.hierarchicalLock.writeLock().lock()

            try {
                val area = hierarchicalGrid.area

                val minChunkX = area.boundingBoxStart.chunkX
                val maxChunkX = area.boundingBoxEnd.chunkX
                val minChunkZ = area.boundingBoxStart.chunkZ
                val maxChunkZ = area.boundingBoxEnd.chunkZ

                for (chunkX in minChunkX..maxChunkX)
                    for (chunkZ in minChunkZ..maxChunkZ) {
                        scanChunkBorders(hierarchicalGrid, chunkX, chunkZ, chunkSnapshots)
                    }

                val localPathfinder = LocalPathfinder(chunkSnapshots, limitToCurrentChunk = true)
                for (cluster in hierarchicalGrid.clusters.values) {
                    val entranceIds = cluster.entranceIds

                    for (entranceIndex in entranceIds.indices) {
                        hierarchicalGrid.entrances[entranceIds[entranceIndex]]?.intraEdges?.clear()
                    }

                    bakeIntraEdges(hierarchicalGrid, cluster, localPathfinder)
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
            val neighborSnapshots = withContext(CoroutineManager.PaperDispatcher(plugin)) {
                captureNeighborSnapshots(world, chunkX, chunkZ)
            }

            withContext(Dispatchers.Default) {
                hierarchicalGrid.hierarchicalLock.writeLock().lock()

                try {
                    hierarchicalGrid.clearClusterData(chunkX, chunkZ)

                    for (deltaX in -1..1)
                        for (deltaZ in -1..1) {
                            scanChunkBorders(hierarchicalGrid, chunkX + deltaX, chunkZ + deltaZ, neighborSnapshots)
                        }

                    val localPathfinder = LocalPathfinder(neighborSnapshots, limitToCurrentChunk = true)

                    val deltaXOffsets = intArrayOf(0, 1, -1, 0, 0)
                    val deltaZOffsets = intArrayOf(0, 0, 0, 1, -1)

                    for (directionIndex in deltaXOffsets.indices) {
                        val targetChunkX = chunkX + deltaXOffsets[directionIndex]
                        val targetChunkZ = chunkZ + deltaZOffsets[directionIndex]
                        val cluster = hierarchicalGrid.getOrCreateCluster(targetChunkX, targetChunkZ)

                        val entranceIds = cluster.entranceIds
                        for (entranceIndex in entranceIds.indices) {
                            hierarchicalGrid.entrances[entranceIds[entranceIndex]]?.intraEdges?.clear()
                        }

                        bakeIntraEdges(hierarchicalGrid, cluster, localPathfinder)
                    }
                } finally {
                    hierarchicalGrid.hierarchicalLock.writeLock().unlock()
                }
            }
        }

        private fun scanChunkBorders(
            grid: HierarchicalGrid, chunkX: Int, chunkZ: Int, snapshots: Long2ObjectMap<ChunkSnapshot>
        ) {
            val currentCluster = grid.getOrCreateCluster(chunkX, chunkZ)
            val borderStartX = chunkX shl 4
            val borderStartZ = chunkZ shl 4

            scanSingleAxis(
                grid,
                currentCluster,
                snapshots,
                chunkX + 1,
                chunkZ,
                true,
                borderStartX,
                borderStartZ
            )
            scanSingleAxis(
                grid,
                currentCluster,
                snapshots,
                chunkX,
                chunkZ + 1,
                false,
                borderStartX,
                borderStartZ
            )
        }

        private fun scanSingleAxis(
            grid: HierarchicalGrid,
            currentCluster: Cluster,
            snapshots: Long2ObjectMap<ChunkSnapshot>,
            targetChunkX: Int,
            targetChunkZ: Int,
            isEastAxis: Boolean,
            borderStartX: Int,
            borderStartZ: Int
        ) {
            val targetChunkKey = getChunkKey(targetChunkX, targetChunkZ)
            if (!snapshots.containsKey(targetChunkKey)) return

            val targetCluster = grid.getOrCreateCluster(targetChunkX, targetChunkZ)

            for (offset in 0..15)
                for (globalY in grid.area.boundingBoxStart.y..grid.area.boundingBoxEnd.y) {
                    val position1 = if (isEastAxis) AreaManager.Position(borderStartX + 15, globalY, borderStartZ + offset)
                    else AreaManager.Position(borderStartX + offset, globalY, borderStartZ + 15)
                    val position2 = if (isEastAxis) AreaManager.Position(borderStartX + 16, globalY, borderStartZ + offset)
                    else AreaManager.Position(borderStartX + offset, globalY, borderStartZ + 16)

                    if (position1 !in grid.area || position2 !in grid.area) continue
                    if (!isPositionWalkable(snapshots, position1) || !isPositionWalkable(snapshots, position2)) continue

                    registerEntrancePair(grid, currentCluster, targetCluster, position1, position2)
                }
        }

        private fun registerEntrancePair(
            grid: HierarchicalGrid, cluster1: Cluster, cluster2: Cluster,
            position1: AreaManager.Position, position2: AreaManager.Position
        ) {
            val entrance1 = grid.entrances.getOrPut(position1.raw) {
                Entrance(position1.raw, position1, cluster1.chunkX, cluster1.chunkZ).also {
                    cluster1.entranceIds.add(it.id)
                }
            }
            val entrance2 = grid.entrances.getOrPut(position2.raw) {
                Entrance(position2.raw, position2, cluster2.chunkX, cluster2.chunkZ).also {
                    cluster2.entranceIds.add(it.id)
                }
            }

            if (entrance1.interEdges.none { it.targetEntranceId == entrance2.id }) {
                entrance1.interEdges.add(AbstractEdge(entrance2.id, 1.0))
            }
            if (entrance2.interEdges.none { it.targetEntranceId == entrance1.id }) {
                entrance2.interEdges.add(AbstractEdge(entrance1.id, 1.0))
            }
        }

        private fun bakeIntraEdges(grid: HierarchicalGrid, cluster: Cluster, pathfinder: LocalPathfinder) {
            val entranceIdList = cluster.entranceIds
            val totalEntrances = entranceIdList.size

            for (outerIndex in 0 until totalEntrances) {
                val sourceEntrance = grid.getEntrance(entranceIdList[outerIndex]) ?: continue

                for (innerIndex in 0 until totalEntrances) {
                    if (outerIndex == innerIndex) continue
                    val targetEntrance = grid.getEntrance(entranceIdList[innerIndex]) ?: continue

                    val localPath = pathfinder.findPath(sourceEntrance.position, targetEntrance.position, grid.area)
                    if (localPath.isEmpty()) continue

                    sourceEntrance.intraEdges.add(AbstractEdge(targetEntrance.id, localPath.calculatePathCost()))
                }
            }
        }

        private fun getBlockMaterial(snapshots: Long2ObjectMap<ChunkSnapshot>, globalX: Int, globalY: Int, globalZ: Int): Material {
            if (globalY < -64 || globalY > 319) return Material.AIR

            val chunkKey = getChunkKey(globalX shr 4, globalZ shr 4)
            val snapshot = snapshots.get(chunkKey) ?: return Material.AIR

            return snapshot.getBlockType(globalX and 15, globalY, globalZ and 15)
        }

        private fun isPositionWalkable(snapshots: Long2ObjectMap<ChunkSnapshot>, position: AreaManager.Position): Boolean =
            !getBlockMaterial(snapshots, position.x, position.y, position.z).isSolid &&
                    !getBlockMaterial(snapshots, position.x, position.y + 1, position.z).isSolid &&
                    getBlockMaterial(snapshots, position.x, position.y - 1, position.z).isSolid
    }


    class HierarchicalPathfinder {
        private class MacroPathNode(
            val positionRaw: Long,
            val estimatedTotalCost: Double
        ) : Comparable<MacroPathNode> {
            override fun compareTo(other: MacroPathNode): Int =
                estimatedTotalCost.compareTo(other.estimatedTotalCost)
        }

        private val accumulatedCostMap = Long2DoubleOpenHashMap().apply {
            defaultReturnValue(Double.MAX_VALUE)
        }
        private val navigationParentMap = Long2LongOpenHashMap().apply {
            defaultReturnValue(-1L)
        }

        suspend fun findHierarchicalPath(
            source: AreaManager.Position,
            target: AreaManager.Position,
            grid: HierarchicalGrid,
            chunkSnapshots: Long2ObjectMap<ChunkSnapshot>
        ): LongArray = withContext(Dispatchers.Default) {
            if (source.raw == target.raw) return@withContext longArrayOf(source.raw)
            if (target !in grid.area) return@withContext LongArray(0)

            val localPathfinder = LocalPathfinder(chunkSnapshots)

            if (source.chunkX == target.chunkX && source.chunkZ == target.chunkZ) {
                return@withContext longArrayOf(source.raw, target.raw)
            }

            grid.hierarchicalLock.readLock().lock()
            try {
                val openSet = PriorityQueue<MacroPathNode>()

                accumulatedCostMap.clear()
                navigationParentMap.clear()

                accumulatedCostMap[source.raw] = 0.0
                openSet.add(
                    MacroPathNode(source.raw, source.distance(target))
                )

                val sourceCluster = grid.clusters[getChunkKey(source.chunkX, source.chunkZ)]
                while (openSet.isNotEmpty()) {
                    val currentRecord = openSet.poll()
                    val currentPositionRaw = currentRecord.positionRaw

                    if (currentPositionRaw == target.raw)
                        return@withContext reconstructMacroPath(navigationParentMap, target.raw)

                    val currentAccumulatedCost = accumulatedCostMap.get(currentPositionRaw)
                    val currentEntrance = if (currentPositionRaw == source.raw) null else grid.entrances[currentPositionRaw] ?: continue
                    val currentPosition = currentEntrance?.position ?: source

                    if (currentRecord.estimatedTotalCost > currentAccumulatedCost + currentPosition.distance(target) + 1e-9) continue

                    if (currentPositionRaw == source.raw) {
                        if (sourceCluster != null) {
                            val entranceIds = sourceCluster.entranceIds

                            for (index in entranceIds.indices) {
                                val entranceId = entranceIds[index]
                                val entrance = grid.entrances[entranceId] ?: continue

                                val localPath = localPathfinder.findPath(source, entrance.position, grid.area)
                                if (localPath.isEmpty()) continue

                                val tentativeAccumulatedCost = currentAccumulatedCost + localPath.calculatePathCost()

                                if (tentativeAccumulatedCost >= accumulatedCostMap.get(entranceId)) continue
                                accumulatedCostMap[entranceId] = tentativeAccumulatedCost
                                navigationParentMap[entranceId] = source.raw

                                openSet.add(
                                    MacroPathNode(entranceId, tentativeAccumulatedCost + entrance.position.distance(target))
                                )
                            }
                        }

                        continue
                    }

                    if (currentEntrance != null && currentEntrance.chunkX == target.chunkX && currentEntrance.chunkZ == target.chunkZ) {
                        val localPath = localPathfinder.findPath(currentPosition, target, grid.area)

                        if (localPath.isNotEmpty()) {
                            val tentativeAccumulatedCost = currentAccumulatedCost + localPath.calculatePathCost()

                            if (tentativeAccumulatedCost < accumulatedCostMap.getOrDefault(target.raw, Double.MAX_VALUE)) {
                                accumulatedCostMap[target.raw] = tentativeAccumulatedCost
                                navigationParentMap[target.raw] = currentPositionRaw

                                openSet.add(
                                    MacroPathNode(target.raw, tentativeAccumulatedCost)
                                )
                            }
                        }
                    }

                    if (currentEntrance != null) {
                        expandEntranceEdges(currentEntrance.intraEdges, currentPositionRaw, currentAccumulatedCost, target, grid, accumulatedCostMap, navigationParentMap, openSet)
                        expandEntranceEdges(currentEntrance.interEdges, currentPositionRaw, currentAccumulatedCost, target, grid, accumulatedCostMap, navigationParentMap, openSet)
                    }
                }

                LongArray(0)
            } finally {
                grid.hierarchicalLock.readLock().unlock()
            }
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
            for (index in edges.indices) {
                val edge = edges[index]
                val neighborEntrance = grid.entrances[edge.targetEntranceId] ?: continue
                val tentativeAccumulatedCost = currentAccumulatedCost + edge.cost

                if (tentativeAccumulatedCost >= accumulatedCostMap.get(edge.targetEntranceId)) continue
                accumulatedCostMap[edge.targetEntranceId] = tentativeAccumulatedCost
                navigationParentMap[edge.targetEntranceId] = currentPositionRaw

                openSet.add(
                    MacroPathNode(edge.targetEntranceId, tentativeAccumulatedCost + neighborEntrance.position.distance(target))
                )
            }
        }

        private fun reconstructMacroPath(
            navigationParentMap: Long2LongOpenHashMap,
            targetPositionRaw: Long
        ): LongArray = ArrayList<Long>().apply {
            var current = targetPositionRaw

            while (current != -1L) {
                add(current)
                current = navigationParentMap.get(current)
            }
        }.let { pathList ->
            LongArray(pathList.size) { index ->
                pathList[pathList.size - 1 - index]
            }
        }
    }


    class HierarchicalNavigator(
        private val plugin: JavaPlugin,
        private val entity: Mob,
        private val hierarchicalGrid: HierarchicalGrid,
        private val scope: CoroutineScope,
        private val gridRegistry: GridRegistry,
        var speed: Double = 1.0
    ) {
        private val mobHeight = ceil(entity.height).toInt()
        private val mobWidth = entity.width

        private var macroPath: LongArray? = null
        private var localPath: LongArray? = null
        private var macroIndex = 0
        private var localIndex = 0

        private var isSearching = false
        private var lastTargetLocation: Location? = null
        private var latestSnapshots: Long2ObjectMap<ChunkSnapshot>? = null

        private val hierarchicalPathfinder = HierarchicalPathfinder()
        private var cachedLocalPathfinder: LocalPathfinder? = null

        fun navigateTo(targetLocation: Location) {
            if (!entity.isValid || entity.isDead) return

            if (targetLocation.toPosition() !in hierarchicalGrid.area) {
                entity.pathfinder.stopPathfinding()
                macroPath = null
                localPath = null

                return
            }

            val mPath = macroPath
            val lastTarget = lastTargetLocation
            val threshold = 1.0

            if ((mPath == null || mPath.isEmpty() || macroIndex >= mPath.size) &&
                entity.location.distanceSquared(targetLocation) <= threshold &&
                lastTarget != null && lastTarget.distanceSquared(targetLocation) <= threshold) {
                return
            }
            if (mPath == null || macroIndex >= mPath.size || lastTarget == null || lastTarget.distanceSquared(targetLocation) > threshold) {
                requestPathAsync(entity.location.toPosition(), targetLocation.toPosition(), targetLocation)
            }

            val activeLocalPath = localPath
            if (activeLocalPath == null || localIndex >= activeLocalPath.size) {
                val currentMacroPath = macroPath ?: run {
                    if (isSearching) {
                        val direction = entity.location.directionTo(targetLocation)
                        val intermediateLocation = entity.location.clone().add(
                            direction.fastNormalize().multiply(2.0)
                        )

                        entity.pathfinder.moveTo(intermediateLocation, speed)
                    }

                    return
                }

                if (macroIndex < currentMacroPath.size - 1) {
                    val snapshots = latestSnapshots ?: gridRegistry.captureNeighborSnapshots(
                        entity.world,
                        entity.location.blockX shr 4,
                        entity.location.blockZ shr 4
                    )

                    val pathfinder = cachedLocalPathfinder?.apply {
                        this.chunkSnapshots = snapshots
                    } ?: LocalPathfinder(
                        snapshots,
                        limitToCurrentChunk = false,
                        mobHeight = mobHeight,
                        mobWidth = mobWidth
                    ).also {
                        cachedLocalPathfinder = it
                    }
                    val generated = pathfinder.findPath(
                        entity.location.toPosition(),
                        AreaManager.Position(currentMacroPath[macroIndex + 1]),
                        hierarchicalGrid.area
                    )

                    if (generated.isEmpty()) {
                        macroPath = null
                        return
                    }

                    localPath = generated
                    localIndex = if (generated.size > 1) 1 else 0

                    macroIndex++
                    triggerMove()
                } else {
                    macroPath = null
                    localPath = null

                    if (isSearching && lastTargetLocation != null) {
                        val direction = entity.location.directionTo(lastTargetLocation!!)
                        val intermediateLocation = entity.location.clone().add(
                            direction.fastNormalize().multiply(2.0)
                        )

                        entity.pathfinder.moveTo(intermediateLocation, speed)
                    }

                    return
                }
            }

            val currentPosition = entity.location.toPosition()
            val currentLocalPath = localPath ?: return
            val targetNodePosition = AreaManager.Position(currentLocalPath[localIndex])

            if (currentPosition.distanceSquared2D(targetNodePosition) < 2.25) {
                if (++localIndex >= currentLocalPath.size) navigateTo(targetLocation)
                else triggerMove()

                return
            }

            if (!entity.pathfinder.hasPath()) {
                triggerMove()
            }
        }

        private fun triggerMove() {
            val lPath = localPath ?: return
            if (localIndex >= lPath.size) return

            val nextNodeLocation = AreaManager.Position(lPath[localIndex]).toLocation(entity.world).add(0.5, 0.0, 0.5)
            entity.pathfinder.moveTo(nextNodeLocation, speed)
        }

        private fun requestPathAsync(
            sourcePosition: AreaManager.Position,
            targetPosition: AreaManager.Position,
            targetLocation: Location
        ) {
            if (isSearching) return
            isSearching = true
            lastTargetLocation = targetLocation.clone()

            val snapshots = gridRegistry.captureAreaSnapshots(entity.world, hierarchicalGrid.area)
            latestSnapshots = snapshots

            scope.launch {
                val resultPath = hierarchicalPathfinder.findHierarchicalPath(
                    sourcePosition,
                    targetPosition,
                    hierarchicalGrid,
                    snapshots
                )

                sync {
                    isSearching = false

                    if (resultPath.isEmpty()) {
                        macroPath = null
                        localPath = null
                        return@sync
                    }

                    macroPath = resultPath
                    macroIndex = 0
                    localPath = null
                    localIndex = 0
                    navigateTo(targetLocation)
                }
            }
        }
    }


    class PathfindingUpdateListener(
        private val plugin: JavaPlugin,
        private val gridRegistry: GridRegistry,
        private val hierarchicalGrid: HierarchicalGrid
    ) : Listener {
        private fun updateGridAt(location: Location) {
            val position = location.toPosition()
            if (position !in hierarchicalGrid.area) return

            later {
                plugin.scope.launch {
                    gridRegistry.rebuildChunk(
                        hierarchicalGrid,
                        position.chunkX,
                        position.chunkZ,
                        location.world,
                        plugin
                    )
                }
            }
        }

        @EventHandler
        fun onBreak(event: BlockBreakEvent) {
            if (event.isCancelled) return

            updateGridAt(event.block.location)
        }

        @EventHandler
        fun onPlace(event: BlockPlaceEvent) {
            if (event.isCancelled) return

            updateGridAt(event.block.location)
        }
    }
}