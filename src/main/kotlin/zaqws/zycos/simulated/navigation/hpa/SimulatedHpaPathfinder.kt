package zaqws.zycos.simulated.navigation.hpa

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.navigation.*
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SimulatedHpaPathfinder(
    private val localPathfinder: SimulatedLocalPathfinder =
        SimulatedAStarPathfinder()
) : SimulatedLocalPathfinder {
    private val cache = SimulatedHpaCache()
    private val localPathCache =
        SimulatedPathCache()

    override fun findPath(
        map: SimulatedMap,
        request: SimulatedPathRequest
    ): SimulatedPathResult =
        findPath(
            map,
            request,
            SimulatedPathCancellation.NEVER
        )

    internal fun findPath(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        cancellation: SimulatedPathCancellation
    ): SimulatedPathResult {
        if (
            cancellation.isCancelled() ||
            map.revision !=
            request.mapRevision
        ) {
            return invalid(request)
        }

        localPathCache.invalidateBefore(
            map,
            request.mapRevision
        )

        val startClusterId =
            SimulatedHpaGraph.clusterId(
                request.start.chunkX,
                request.start.chunkZ
            )

        val targetClusterId =
            SimulatedHpaGraph.clusterId(
                request.target.chunkX,
                request.target.chunkZ
            )

        if (
            startClusterId ==
            targetClusterId
        ) {
            return findLocalPathfinderPath(map, request, cancellation)
        }

        val graph =
            cache.graph(
                map,
                request.traversalProfile,
                request.mapRevision,
                cancellation
            ) ?: return invalid(request)

        val startCluster =
            graph.cluster(
                startClusterId
            ) ?: return invalid(request)

        val targetCluster =
            graph.cluster(
                targetClusterId
            ) ?: return invalid(request)

        if (
            startCluster.isEmpty ||
            targetCluster.isEmpty
        ) {
            return unreachable(request)
        }

        val abstractPath =
            findAbstractPath(
                map = map,
                graph = graph,
                request = request,
                startCluster = startCluster,
                targetCluster = targetCluster,
                cancellation = cancellation
            ) ?: return if (cancellation.isCancelled() || map.revision != request.mapRevision) invalid(request) else unreachable(request)

        return refinePath(
            map = map,
            request = request,
            abstractPath = abstractPath,
            cancellation = cancellation
        )
    }

    private fun findAbstractPath(
        map: SimulatedMap,
        graph: SimulatedHpaGraph,
        request: SimulatedPathRequest,
        startCluster: SimulatedHpaCluster,
        targetCluster: SimulatedHpaCluster,
        cancellation: SimulatedPathCancellation
    ): AbstractPath? {
        val openNodes =
            PriorityQueue(
                compareBy<AbstractCandidate> {
                    it.totalEstimatedCost
                }.thenBy {
                    it.heuristicCost
                }
            )

        val pathCosts =
            HashMap<AbstractNode, Double>()

        val previousNodes =
            HashMap<
                    AbstractNode,
                    AbstractNode
                    >()

        val closedNodes =
            HashSet<AbstractNode>()

        val startNode =
            AbstractNode.Endpoint(
                request.start
            )

        val targetNode =
            AbstractNode.Endpoint(
                request.target
            )

        pathCosts[startNode] =
            0.0

        openNodes.add(
            AbstractCandidate(
                node =
                    startNode,

                pathCost =
                    0.0,

                heuristicCost =
                    heuristic(
                        request.start,
                        request.target
                    )
            )
        )

        while (openNodes.isNotEmpty()) {
            if (
                cancellation.isCancelled() ||
                map.revision !=
                request.mapRevision ||
                Thread.currentThread().isInterrupted
            ) {
                return null
            }

            val candidate =
                openNodes.poll()

            val currentNode =
                candidate.node

            val currentPathCost =
                pathCosts[
                    currentNode
                ] ?: continue

            if (
                candidate.pathCost >
                currentPathCost
            ) {
                continue
            }

            if (
                !closedNodes.add(
                    currentNode
                )
            ) {
                continue
            }

            if (
                currentNode ==
                targetNode
            ) {
                return reconstructAbstractPath(
                    previousNodes,
                    targetNode
                )
            }

            forEachAbstractNeighbor(
                map = map,
                graph = graph,
                request = request,
                currentNode = currentNode,
                startCluster = startCluster,
                targetCluster = targetCluster,
                cancellation = cancellation
            ) { neighbor, movementCost ->
                if (
                    neighbor in
                    closedNodes
                ) {
                    return@forEachAbstractNeighbor
                }

                val nextPathCost =
                    currentPathCost +
                            movementCost

                val previousPathCost =
                    pathCosts[
                        neighbor
                    ]

                if (
                    previousPathCost != null &&
                    nextPathCost >=
                    previousPathCost
                ) {
                    return@forEachAbstractNeighbor
                }

                pathCosts[neighbor] =
                    nextPathCost

                previousNodes[neighbor] =
                    currentNode

                val heuristicCost =
                    heuristic(
                        neighbor.navigationNode,
                        request.target
                    )

                openNodes.add(
                    AbstractCandidate(
                        node =
                            neighbor,

                        pathCost =
                            nextPathCost,

                        heuristicCost =
                            heuristicCost
                    )
                )
            }
        }

        return null
    }

    private inline fun forEachAbstractNeighbor(
        map: SimulatedMap,
        graph: SimulatedHpaGraph,
        request: SimulatedPathRequest,
        currentNode: AbstractNode,
        startCluster: SimulatedHpaCluster,
        targetCluster: SimulatedHpaCluster,
        cancellation: SimulatedPathCancellation,
        action: (
            AbstractNode,
            Double
        ) -> Unit
    ) {
        when (currentNode) {
            is AbstractNode.Endpoint -> {
                val cluster =
                    if (
                        currentNode.navigationNode ==
                        request.start
                    ) {
                        startCluster
                    } else {
                        targetCluster
                    }

                if (
                    currentNode.navigationNode ==
                    request.target
                ) {
                    return
                }

                var portalIndex = 0

                while (
                    portalIndex <
                    cluster.portalCount
                ) {
                    val portal =
                        cluster.portalAt(
                            portalIndex++
                        )

                    val portalNode =
                        portal.nodeFor(
                            cluster.clusterId
                        )

                    val cost =
                        localCost(
                            map = map,
                            request = request,
                            start =
                                currentNode.navigationNode,
                            target =
                                portalNode,
                            cancellation = cancellation
                        ) ?: continue

                    action(
                        AbstractNode.Portal(
                            portalId =
                                portal.portalId,

                            clusterId =
                                cluster.clusterId,

                            navigationNode =
                                portalNode
                        ),
                        cost
                    )
                }
            }

            is AbstractNode.Portal -> {
                val portal =
                    graph.portal(
                        currentNode.portalId
                    ) ?: return

                val otherClusterId =
                    portal.otherCluster(
                        currentNode.clusterId
                    )

                val otherNode =
                    portal.otherNode(
                        currentNode.clusterId
                    )

                if (
                    canCrossPortal(
                        map = map,
                        source =
                            currentNode.navigationNode,
                        target = otherNode,
                        request = request
                    )
                ) {
                    action(
                        AbstractNode.Portal(
                            portalId =
                                portal.portalId,

                            clusterId =
                                otherClusterId,

                            navigationNode =
                                otherNode
                        ),
                        crossPortalCost(
                            currentNode.navigationNode,
                            otherNode
                        )
                    )
                }

                val cluster =
                    graph.cluster(
                        currentNode.clusterId
                    ) ?: return

                var portalIndex = 0

                while (
                    portalIndex <
                    cluster.portalCount
                ) {
                    val otherPortal =
                        cluster.portalAt(
                            portalIndex++
                        )

                    if (
                        otherPortal.portalId ==
                        currentNode.portalId
                    ) {
                        continue
                    }

                    val otherPortalNode =
                        otherPortal.nodeFor(
                            cluster.clusterId
                        )

                    val cost =
                        localCost(
                            map = map,
                            request = request,
                            start =
                                currentNode.navigationNode,
                            target =
                                otherPortalNode,
                            cancellation = cancellation
                        ) ?: continue

                    action(
                        AbstractNode.Portal(
                            portalId =
                                otherPortal.portalId,

                            clusterId =
                                cluster.clusterId,

                            navigationNode =
                                otherPortalNode
                        ),
                        cost
                    )
                }

                if (
                    cluster.clusterId ==
                    targetCluster.clusterId
                ) {
                    val cost =
                        localCost(
                            map = map,
                            request = request,
                            start =
                                currentNode.navigationNode,
                            target =
                                request.target,
                            cancellation = cancellation
                        )

                    if (cost != null) {
                        action(
                            AbstractNode.Endpoint(
                                request.target
                            ),
                            cost
                        )
                    }
                }
            }
        }
    }

    private fun localCost(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        start: NavigationNode,
        target: NavigationNode,
        cancellation: SimulatedPathCancellation
    ): Double? {
        if (start == target) {
            return 0.0
        }

        val result =
            findLocalPath(
                map = map,
                request = request,
                start = start,
                target = target,
                cancellation = cancellation
            )

        return when (result) {
            is SimulatedPathResult.Success ->
                result.totalCost

            is SimulatedPathResult.Invalid,
            is SimulatedPathResult.Unreachable ->
                null
        }
    }

    private fun crossPortalCost(
        first: NavigationNode,
        second: NavigationNode
    ): Double {
        val differenceX =
            abs(
                first.x -
                        second.x
            )

        val differenceZ =
            abs(
                first.z -
                        second.z
            )

        val horizontalCost =
            if (
                differenceX != 0 &&
                differenceZ != 0
            ) {
                DIAGONAL_COST
            } else {
                STRAIGHT_COST
            }

        val verticalDifference =
            abs(
                first.floorHeightUnits -
                        second.floorHeightUnits
            ).toDouble() /
                    HEIGHT_COST_DIVISOR

        return horizontalCost +
                verticalDifference
    }

    private fun canCrossPortal(
        map: SimulatedMap,
        source: NavigationNode,
        target: NavigationNode,
        request: SimulatedPathRequest
    ): Boolean {
        val verticalDifferenceUnits =
            target.floorHeightUnits -
                    source.floorHeightUnits

        if (
            verticalDifferenceUnits >
            request.traversalProfile
                .maximumStepHeightUnits ||
            verticalDifferenceUnits <
            -request.maximumDropHeightUnits
        ) {
            return false
        }

        val profile = request.traversalProfile
        val halfWidth = profile.width * 0.5
        val sourcePosition = source.toPosition()
        val targetPosition = target.toPosition()
        val maximumY = max(sourcePosition.y, targetPosition.y) + profile.height

        val jumpSweptBox =
            zaqws.zycos.simulated.math.SimulatedAABB(
                minimumX =
                    min(
                        sourcePosition.x,
                        targetPosition.x
                    ) -
                            halfWidth,

                minimumY =
                    max(
                        sourcePosition.y,
                        targetPosition.y
                    ),

                minimumZ =
                    min(
                        sourcePosition.z,
                        targetPosition.z
                    ) -
                            halfWidth,

                maximumX =
                    max(
                        sourcePosition.x,
                        targetPosition.x
                    ) +
                            halfWidth,

                maximumY =
                    maximumY,

                maximumZ =
                    max(
                        sourcePosition.z,
                        targetPosition.z
                    ) +
                            halfWidth
            )

        return !map.hasCollision(jumpSweptBox)
    }

    private fun refinePath(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        abstractPath: AbstractPath,
        cancellation: SimulatedPathCancellation
    ): SimulatedPathResult {
        if (
            cancellation.isCancelled() ||
            map.revision !=
            request.mapRevision
        ) {
            return invalid(request)
        }

        val finalNodes =
            ArrayList<NavigationNode>()

        var totalCost = 0.0

        var index = 0

        while (
            index <
            abstractPath.nodes.size - 1
        ) {
            if (
                cancellation.isCancelled() ||
                map.revision !=
                request.mapRevision ||
                Thread.currentThread().isInterrupted
            ) {
                return invalid(request)
            }

            val start =
                abstractPath.nodes[index]
                    .navigationNode

            val target =
                abstractPath.nodes[
                    index + 1
                ].navigationNode

            if (
                start.chunkX !=
                target.chunkX ||
                start.chunkZ !=
                target.chunkZ
            ) {
                if (finalNodes.isEmpty()) {
                    finalNodes.add(start)
                }

                if (
                    finalNodes.last() !=
                    target
                ) {
                    finalNodes.add(target)
                }

                totalCost +=
                    crossPortalCost(
                        start,
                        target
                    )

                index++
                continue
            }

            val localResult =
                findLocalPath(
                    map = map,
                    request = request,
                    start = start,
                    target = target,
                    cancellation = cancellation
                )

            if (
                localResult !is
                        SimulatedPathResult.Success
            ) {
                return if (localResult is SimulatedPathResult.Invalid || cancellation.isCancelled() || map.revision != request.mapRevision) {
                    invalid(request)
                } else {
                    unreachable(request)
                }
            }

            appendPath(
                finalNodes,
                localResult.path
            )

            totalCost +=
                localResult.totalCost

            index++
        }

        if (finalNodes.isEmpty()) {
            return unreachable(
                request
            )
        }

        return SimulatedPathResult.Success(
            requestId =
                request.requestId,

            entityId =
                request.entityId,

            mapRevision =
                request.mapRevision,

            path =
                SimulatedPath(
                    finalNodes
                ),

            totalCost =
                totalCost
        )
    }

    private fun appendPath(
        destination: MutableList<NavigationNode>,
        path: SimulatedPath
    ) {
        var index = 0

        if (
            destination.isNotEmpty() &&
            destination.last() ==
            path.start
        ) {
            index = 1
        }

        while (index < path.size) {
            destination.add(
                path[index]
            )

            index++
        }
    }

    private fun findLocalPath(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        start: NavigationNode,
        target: NavigationNode,
        cancellation: SimulatedPathCancellation
    ): SimulatedPathResult {
        if (cancellation.isCancelled() || map.revision != request.mapRevision) {
            return invalid(request)
        }

        val localRequest =
            request.copy(
                start = start,
                target = target
            )

        val cachedResult =
            localPathCache.get(
                map,
                localRequest
            )

        if (cachedResult != null) {
            return cachedResult
        }

        val result =
            if (
                start.chunkX == target.chunkX &&
                start.chunkZ == target.chunkZ &&
                localPathfinder is
                        SimulatedAStarPathfinder
            ) {
                localPathfinder
                    .findPathInChunk(
                        map = map,
                        request = localRequest,
                        chunkX = start.chunkX,
                        chunkZ = start.chunkZ,
                        cancellation = cancellation
                    )
            } else {
                findLocalPathfinderPath(map, localRequest, cancellation)
            }

        if (cancellation.isCancelled() || map.revision != request.mapRevision) {
            return invalid(localRequest)
        }

        if (
            result !is
                    SimulatedPathResult.Success
        ) {
            localPathCache.put(
                map,
                localRequest,
                result
            )

            return result
        }

        if (
            start.chunkX != target.chunkX ||
            start.chunkZ != target.chunkZ
        ) {
            localPathCache.put(
                map,
                localRequest,
                result
            )

            return result
        }

        var index = 0

        while (index < result.path.size) {
            val node =
                result.path[index]

            if (
                node.chunkX != start.chunkX ||
                node.chunkZ != start.chunkZ
            ) {
                val unreachableResult =
                    unreachable(
                        localRequest
                    )

                localPathCache.put(
                    map,
                    localRequest,
                    unreachableResult
                )

                return unreachableResult
            }

            index++
        }

        localPathCache.put(
            map,
            localRequest,
            result
        )

        return result
    }

    private fun findLocalPathfinderPath(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        cancellation: SimulatedPathCancellation
    ): SimulatedPathResult =
        when (val pathfinder = localPathfinder) {
            is SimulatedAStarPathfinder ->
                pathfinder.findPath(map, request, cancellation)

            is SimulatedHpaPathfinder ->
                pathfinder.findPath(map, request, cancellation)

            else ->
                pathfinder.findPath(map, request)
        }

    private fun reconstructAbstractPath(
        previousNodes: Map<
                AbstractNode,
                AbstractNode
                >,
        targetNode: AbstractNode
    ): AbstractPath {
        val reversedNodes =
            ArrayList<AbstractNode>()

        var currentNode =
            targetNode

        reversedNodes.add(
            currentNode
        )

        while (true) {
            val previousNode =
                previousNodes[
                    currentNode
                ] ?: break

            currentNode =
                previousNode

            reversedNodes.add(
                currentNode
            )
        }

        reversedNodes.reverse()

        return AbstractPath(
            reversedNodes
        )
    }

    private fun heuristic(
        source: NavigationNode,
        target: NavigationNode
    ): Double {
        val differenceX =
            abs(
                target.x -
                        source.x
            )

        val differenceZ =
            abs(
                target.z -
                        source.z
            )

        val diagonal =
            min(
                differenceX,
                differenceZ
            )

        val straight =
            max(
                differenceX,
                differenceZ
            ) -
                    diagonal

        return diagonal *
                DIAGONAL_COST +
                straight *
                STRAIGHT_COST
    }

    private fun invalid(
        request: SimulatedPathRequest
    ) = SimulatedPathResult.Invalid(
        requestId =
            request.requestId,

        entityId =
            request.entityId,

        mapRevision =
            request.mapRevision
    )

    private fun unreachable(
        request: SimulatedPathRequest
    ) = SimulatedPathResult.Unreachable(
        requestId =
            request.requestId,

        entityId =
            request.entityId,

        mapRevision =
            request.mapRevision
    )

    private sealed interface AbstractNode {
        val navigationNode: NavigationNode

        data class Endpoint(
            override val navigationNode:
            NavigationNode
        ) : AbstractNode

        data class Portal(
            val portalId: Int,
            val clusterId: Long,
            override val navigationNode:
            NavigationNode
        ) : AbstractNode
    }

    private data class AbstractCandidate(
        val node: AbstractNode,
        val pathCost: Double,
        val heuristicCost: Double
    ) {
        val totalEstimatedCost: Double
            get() =
                pathCost +
                        heuristicCost
    }

    private data class AbstractPath(
        val nodes: List<AbstractNode>
    )

    companion object {
        private const val STRAIGHT_COST =
            1.0

        private const val DIAGONAL_COST =
            1.4142135623730951

        private const val HEIGHT_COST_DIVISOR =
            16.0
    }
}
