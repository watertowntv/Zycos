@file:Suppress("unused")

package zaqws.zycos.simulated.navigation.hpa

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.navigation.*
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SimulatedHpaPathfinder(
    private val localPathfinder: SimulatedLocalPathfinder =
        SimulatedAStarPathfinder(),
    private val cache: SimulatedHpaCache =
        SimulatedHpaCache()
) : SimulatedLocalPathfinder {
    override fun findPath(
        map: SimulatedMap,
        request: SimulatedPathRequest
    ): SimulatedPathResult {
        if (
            map.revision !=
            request.mapRevision
        ) {
            return invalid(request)
        }

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
            return localPathfinder.findPath(
                map,
                request
            )
        }

        val graph =
            cache.graph(
                map,
                request.traversalProfile
            )

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
                targetCluster = targetCluster
            ) ?: return unreachable(request)

        return refinePath(
            map = map,
            request = request,
            abstractPath = abstractPath
        )
    }

    private fun findAbstractPath(
        map: SimulatedMap,
        graph: SimulatedHpaGraph,
        request: SimulatedPathRequest,
        startCluster: SimulatedHpaCluster,
        targetCluster: SimulatedHpaCluster
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
                map.revision !=
                request.mapRevision
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
                targetCluster = targetCluster
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
                                portalNode
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
                                otherPortalNode
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
                                request.target
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
        target: NavigationNode
    ): Double? {
        if (start == target) {
            return 0.0
        }

        val result =
            localPathfinder.findPath(
                map,
                request.copy(
                    start = start,
                    target = target
                )
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

    private fun refinePath(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        abstractPath: AbstractPath
    ): SimulatedPathResult {
        val finalNodes =
            ArrayList<NavigationNode>()

        var totalCost = 0.0

        var index = 0

        while (
            index <
            abstractPath.nodes.size - 1
        ) {
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
                localPathfinder.findPath(
                    map,
                    request.copy(
                        start = start,
                        target = target
                    )
                )

            if (
                localResult !is
                        SimulatedPathResult.Success
            ) {
                return unreachable(
                    request
                )
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