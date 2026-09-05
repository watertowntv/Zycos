package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.WalkSurface
import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedMath
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SimulatedAStarPathfinder(
    private val maximumVisitedNodes: Int =
        DEFAULT_MAXIMUM_VISITED_NODES
) : SimulatedLocalPathfinder {
    companion object {
        private const val DEFAULT_MAXIMUM_VISITED_NODES = 250_000

        private val DIRECTIONS = arrayOf(
            Direction(0, -1),
            Direction(1, 0),
            Direction(0, 1),
            Direction(-1, 0),

            Direction(1, -1),
            Direction(1, 1),
            Direction(-1, 1),
            Direction(-1, -1)
        )
    }

    init {
        require(maximumVisitedNodes > 0)
    }

    override fun findPath(
        map: SimulatedMap,
        request: SimulatedPathRequest
    ): SimulatedPathResult =
        findPath(
            map = map,
            request = request,
            allowedChunkX = null,
            allowedChunkZ = null
        )

    internal fun findPathInChunk(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        chunkX: Int,
        chunkZ: Int
    ): SimulatedPathResult =
        findPath(
            map = map,
            request = request,
            allowedChunkX = chunkX,
            allowedChunkZ = chunkZ
        )

    private fun findPath(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        allowedChunkX: Int?,
        allowedChunkZ: Int?
    ): SimulatedPathResult {
        if (
            request.cancellation.isCancelled() ||
            map.revision !=
            request.mapRevision
        ) {
            return invalid(request)
        }

        if (
            allowedChunkX != null &&
            (
                    request.start.chunkX !=
                            allowedChunkX ||
                            request.start.chunkZ !=
                            allowedChunkZ ||
                            request.target.chunkX !=
                            allowedChunkX ||
                            request.target.chunkZ !=
                            allowedChunkZ
                    ) ||
            !isValidNode(
                map,
                request.start,
                request.traversalProfile
            ) ||
            !isValidNode(
                map,
                request.target,
                request.traversalProfile
            )
        ) {
            return invalid(request)
        }

        if (
            request.start ==
            request.target
        ) {
            return SimulatedPathResult.Success(
                requestId =
                    request.requestId,

                entityId =
                    request.entityId,

                mapRevision =
                    request.mapRevision,

                path =
                    SimulatedPath(
                        listOf(
                            request.start
                        )
                    ),

                totalCost =
                    0.0
            )
        }

        val openNodes =
            PriorityQueue(
                compareBy<NodeCandidate> {
                    it.estimatedTotalCost
                }.thenBy {
                    it.heuristicCost
                }
            )

        val pathCosts =
            HashMap<NavigationNode, Double>()

        val previousNodes =
            HashMap<
                    NavigationNode,
                    NavigationNode
                    >()

        val closedNodes =
            HashSet<NavigationNode>()

        val startHeuristic =
            heuristic(
                request.start,
                request.target
            )

        pathCosts[request.start] =
            0.0

        openNodes.add(
            NodeCandidate(
                node =
                    request.start,

                pathCost =
                    0.0,

                heuristicCost =
                    startHeuristic
            )
        )

        var visitedNodes = 0

        while (openNodes.isNotEmpty()) {
            if (
                request.cancellation.isCancelled() ||
                map.revision !=
                request.mapRevision ||
                Thread.currentThread().isInterrupted
            ) {
                return invalid(request)
            }

            val candidate =
                openNodes.poll()

            val currentNode =
                candidate.node

            val currentPathCost =
                pathCosts[currentNode]
                    ?: continue

            if (
                candidate.pathCost >
                currentPathCost +
                SimulatedMath.EPSILON
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

            visitedNodes++

            if (
                visitedNodes >
                maximumVisitedNodes
            ) {
                return unreachable(
                    request
                )
            }

            if (
                currentNode ==
                request.target
            ) {
                return createSuccess(
                    request =
                        request,

                    previousNodes =
                        previousNodes,

                    totalCost =
                        currentPathCost
                )
            }

            forEachNeighbor(
                map = map,
                source = currentNode,
                profile =
                    request.traversalProfile,
                maximumDropHeightUnits =
                    request.maximumDropHeightUnits
            ) { edge ->
                val targetNode =
                    edge.target

                if (
                    allowedChunkX != null &&
                    (
                            targetNode.chunkX !=
                                    allowedChunkX ||
                                    targetNode.chunkZ !=
                                    allowedChunkZ
                            )
                ) {
                    return@forEachNeighbor
                }

                if (
                    targetNode in
                    closedNodes
                ) {
                    return@forEachNeighbor
                }

                val nextPathCost =
                    currentPathCost +
                            edge.cost

                val previousPathCost =
                    pathCosts[
                        targetNode
                    ]

                if (
                    previousPathCost != null &&
                    nextPathCost >=
                    previousPathCost -
                    SimulatedMath.EPSILON
                ) {
                    return@forEachNeighbor
                }

                pathCosts[targetNode] =
                    nextPathCost

                previousNodes[targetNode] =
                    currentNode

                val heuristicCost =
                    heuristic(
                        targetNode,
                        request.target
                    )

                openNodes.add(
                    NodeCandidate(
                        node =
                            targetNode,

                        pathCost =
                            nextPathCost,

                        heuristicCost =
                            heuristicCost
                    )
                )
            }
        }

        return unreachable(
            request
        )
    }

    private inline fun forEachNeighbor(
        map: SimulatedMap,
        source: NavigationNode,
        profile: SimulatedTraversalProfile,
        maximumDropHeightUnits: Int,
        action: (NavigationEdge) -> Unit
    ) {
        for (
        direction in
        DIRECTIONS
        ) {
            val targetX =
                source.x +
                        direction.x

            val targetZ =
                source.z +
                        direction.z

            val surfaceCount =
                map.surfaceCountAt(
                    targetX,
                    targetZ
                )

            if (surfaceCount == 0) {
                continue
            }

            var surfaceIndex = 0

            while (
                surfaceIndex <
                surfaceCount
            ) {
                val surface =
                    map.surfaceAt(
                        targetX,
                        targetZ,
                        surfaceIndex
                    )

                surfaceIndex++

                if (surface == null) {
                    continue
                }

                val target =
                    NavigationNode(
                        x =
                            targetX,

                        z =
                            targetZ,

                        floorHeightUnits =
                            surface.floorHeightUnits
                    )

                if (
                    !canTransition(
                        map = map,
                        source = source,
                        target = target,
                        targetSurface = surface,
                        profile = profile,
                        maximumDropHeightUnits =
                            maximumDropHeightUnits
                    )
                ) {
                    continue
                }

                if (
                    direction.diagonal &&
                    !canMoveDiagonally(
                        map = map,
                        source = source,
                        target = target,
                        profile = profile,
                        maximumDropHeightUnits =
                            maximumDropHeightUnits
                    )
                ) {
                    continue
                }

                action(
                    NavigationEdge.between(
                        source,
                        target
                    )
                )
            }
        }
    }

    private fun canTransition(
        map: SimulatedMap,
        source: NavigationNode,
        target: NavigationNode,
        targetSurface: WalkSurface,
        profile: SimulatedTraversalProfile,
        maximumDropHeightUnits: Int
    ): Boolean {
        if (
            targetSurface.clearanceUnits <
            profile.heightUnits
        ) {
            return false
        }

        if (
            targetSurface.waterDepthUnits >
            map.config
                .maximumWalkableWaterDepthUnits
        ) {
            return false
        }

        val verticalDifferenceUnits =
            target.floorHeightUnits -
                    source.floorHeightUnits

        if (
            verticalDifferenceUnits >
            profile.maximumStepHeightUnits
        ) {
            return false
        }

        if (
            verticalDifferenceUnits <
            -maximumDropHeightUnits
        ) {
            return false
        }

        if (
            !canOccupy(
                map,
                target,
                profile
            )
        ) {
            return false
        }

        return canTraverseBetween(
            map = map,
            source = source,
            target = target,
            profile = profile
        )
    }

    private fun canMoveDiagonally(
        map: SimulatedMap,
        source: NavigationNode,
        target: NavigationNode,
        profile: SimulatedTraversalProfile,
        maximumDropHeightUnits: Int
    ): Boolean {
        val firstIntermediateX =
            target.x

        val firstIntermediateZ =
            source.z

        val secondIntermediateX =
            source.x

        val secondIntermediateZ =
            target.z

        return hasValidIntermediateSurface(
            map = map,
            source = source,
            target = target,
            intermediateX =
                firstIntermediateX,
            intermediateZ =
                firstIntermediateZ,
            profile = profile,
            maximumDropHeightUnits =
                maximumDropHeightUnits
        ) &&
                hasValidIntermediateSurface(
                    map = map,
                    source = source,
                    target = target,
                    intermediateX =
                        secondIntermediateX,
                    intermediateZ =
                        secondIntermediateZ,
                    profile = profile,
                    maximumDropHeightUnits =
                        maximumDropHeightUnits
                )
    }

    private fun hasValidIntermediateSurface(
        map: SimulatedMap,
        source: NavigationNode,
        target: NavigationNode,
        intermediateX: Int,
        intermediateZ: Int,
        profile: SimulatedTraversalProfile,
        maximumDropHeightUnits: Int
    ): Boolean {
        val surfaceCount =
            map.surfaceCountAt(
                intermediateX,
                intermediateZ
            )

        var surfaceIndex = 0

        while (
            surfaceIndex <
            surfaceCount
        ) {
            val surface =
                map.surfaceAt(
                    intermediateX,
                    intermediateZ,
                    surfaceIndex
                )

            surfaceIndex++

            if (surface == null) {
                continue
            }

            val intermediate =
                NavigationNode(
                    x =
                        intermediateX,

                    z =
                        intermediateZ,

                    floorHeightUnits =
                        surface.floorHeightUnits
                )

            if (
                !canTransition(
                    map = map,
                    source = source,
                    target = intermediate,
                    targetSurface = surface,
                    profile = profile,
                    maximumDropHeightUnits =
                        maximumDropHeightUnits
                )
            ) {
                continue
            }

            val targetSurface =
                findSurface(
                    map,
                    target
                ) ?: continue

            if (
                canTransition(
                    map = map,
                    source = intermediate,
                    target = target,
                    targetSurface =
                        targetSurface,
                    profile = profile,
                    maximumDropHeightUnits =
                        maximumDropHeightUnits
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun canTraverseBetween(
        map: SimulatedMap,
        source: NavigationNode,
        target: NavigationNode,
        profile: SimulatedTraversalProfile
    ): Boolean {
        val sourcePosition =
            source.toPosition()

        val targetPosition =
            target.toPosition()

        val halfWidth =
            profile.width * 0.5

        val maximumY =
            max(
                sourcePosition.y,
                targetPosition.y
            ) +
                    profile.height

        if (
            !canOccupy(
                map,
                source,
                profile
            ) ||
            !canOccupy(
                map,
                target,
                profile
            )
        ) {
            return false
        }

        val jumpSweptBox =
            SimulatedAABB(
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

        return !map.hasCollision(
            jumpSweptBox
        )
    }

    private fun isValidNode(
        map: SimulatedMap,
        node: NavigationNode,
        profile: SimulatedTraversalProfile
    ): Boolean {
        val surface =
            findSurface(
                map,
                node
            ) ?: return false

        if (
            surface.clearanceUnits <
            profile.heightUnits
        ) {
            return false
        }

        if (
            surface.waterDepthUnits >
            map.config
                .maximumWalkableWaterDepthUnits
        ) {
            return false
        }

        return canOccupy(
            map,
            node,
            profile
        )
    }

    private fun canOccupy(
        map: SimulatedMap,
        node: NavigationNode,
        profile: SimulatedTraversalProfile
    ): Boolean {
        val boundingBox =
            SimulatedAABB.fromBottomCenter(
                position =
                    node.toPosition(),

                width =
                    profile.width,

                height =
                    profile.height
            )

        return !map.hasCollision(
            boundingBox
        )
    }

    private fun findSurface(
        map: SimulatedMap,
        node: NavigationNode
    ): WalkSurface? {
        val surfaceCount =
            map.surfaceCountAt(
                node.x,
                node.z
            )

        var surfaceIndex = 0

        while (
            surfaceIndex <
            surfaceCount
        ) {
            val surface =
                map.surfaceAt(
                    node.x,
                    node.z,
                    surfaceIndex
                )

            if (
                surface != null &&
                surface.floorHeightUnits ==
                node.floorHeightUnits
            ) {
                return surface
            }

            surfaceIndex++
        }

        return null
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

        val diagonalDistance =
            min(
                differenceX,
                differenceZ
            )

        val straightDistance =
            max(
                differenceX,
                differenceZ
            ) -
                    diagonalDistance

        return diagonalDistance *
                NavigationEdge.DIAGONAL_COST +
                straightDistance *
                NavigationEdge.STRAIGHT_COST
    }

    private fun createSuccess(
        request: SimulatedPathRequest,
        previousNodes: Map<
                NavigationNode,
                NavigationNode
                >,
        totalCost: Double
    ): SimulatedPathResult.Success {
        val reversedPath =
            ArrayList<NavigationNode>()

        var currentNode =
            request.target

        reversedPath.add(
            currentNode
        )

        while (
            currentNode !=
            request.start
        ) {
            currentNode =
                previousNodes[currentNode]
                    ?: return SimulatedPathResult.Success(
                        requestId =
                            request.requestId,

                        entityId =
                            request.entityId,

                        mapRevision =
                            request.mapRevision,

                        path =
                            SimulatedPath(
                                listOf(
                                    request.start
                                )
                            ),

                        totalCost =
                            0.0
                    )

            reversedPath.add(
                currentNode
            )
        }

        reversedPath.reverse()

        return SimulatedPathResult.Success(
            requestId =
                request.requestId,

            entityId =
                request.entityId,

            mapRevision =
                request.mapRevision,

            path =
                SimulatedPath(
                    reversedPath
                ),

            totalCost =
                totalCost
        )
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

    private data class NodeCandidate(
        val node: NavigationNode,
        val pathCost: Double,
        val heuristicCost: Double
    ) {
        val estimatedTotalCost: Double
            get() =
                pathCost +
                        heuristicCost
    }

    private data class Direction(
        val x: Int,
        val z: Int
    ) {
        val diagonal: Boolean
            get() =
                x != 0 &&
                        z != 0
    }
}
