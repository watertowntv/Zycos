@file:Suppress("unused")

package zaqws.zycos.simulated.navigation.jps

import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.map.WalkSurface
import zaqws.zycos.simulated.math.SimulatedAABB
import zaqws.zycos.simulated.math.SimulatedMath
import zaqws.zycos.simulated.navigation.NavigationEdge
import zaqws.zycos.simulated.navigation.NavigationNode
import zaqws.zycos.simulated.navigation.SimulatedAStarPathfinder
import zaqws.zycos.simulated.navigation.SimulatedLocalPathfinder
import zaqws.zycos.simulated.navigation.SimulatedPath
import zaqws.zycos.simulated.navigation.SimulatedPathRequest
import zaqws.zycos.simulated.navigation.SimulatedPathResult
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SimulatedJpsPathfinder(
    private val fallbackPathfinder:
    SimulatedLocalPathfinder =
        SimulatedAStarPathfinder(),
    private val maximumVisitedJumpPoints: Int =
        DEFAULT_MAXIMUM_VISITED_JUMP_POINTS
) : SimulatedLocalPathfinder {
    init {
        require(
            maximumVisitedJumpPoints > 0
        )
    }

    override fun findPath(
        map: SimulatedMap,
        request: SimulatedPathRequest
    ): SimulatedPathResult {
        if (
            map.revision !=
            request.mapRevision
        ) {
            return invalid(
                request
            )
        }

        if (
            !canUseJumpPointSearch(
                map,
                request
            )
        ) {
            return fallbackPathfinder.findPath(
                map,
                request
            )
        }

        val result =
            findJumpPointPath(
                map,
                request
            )

        return result
            ?: fallbackPathfinder.findPath(
                map,
                request
            )
    }

    private fun findJumpPointPath(
        map: SimulatedMap,
        request: SimulatedPathRequest
    ): SimulatedPathResult? {
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
            PriorityQueue<NodeCandidate>(
                compareBy<NodeCandidate> {
                    it.totalEstimatedCost
                }.thenBy {
                    it.heuristicCost
                }
            )

        val pathCosts =
            HashMap<
                    NavigationNode,
                    Double
                    >()

        val previousNodes =
            HashMap<
                    NavigationNode,
                    NavigationNode
                    >()

        val closedNodes =
            HashSet<NavigationNode>()

        pathCosts[
            request.start
        ] = 0.0

        openNodes.add(
            NodeCandidate(
                node =
                    request.start,

                pathCost =
                    0.0,

                heuristicCost =
                    heuristic(
                        request.start,
                        request.target
                    )
            )
        )

        var visitedJumpPoints = 0

        while (
            openNodes.isNotEmpty()
        ) {
            if (
                map.revision !=
                request.mapRevision
            ) {
                return invalid(
                    request
                )
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

            visitedJumpPoints++

            if (
                visitedJumpPoints >
                maximumVisitedJumpPoints
            ) {
                return null
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

            for (
            direction in
            DIRECTIONS
            ) {
                val jumpPoint =
                    jump(
                        map = map,
                        request = request,
                        source =
                            currentNode,
                        directionX =
                            direction.x,
                        directionZ =
                            direction.z
                    ) ?: continue

                val targetNode =
                    jumpPoint.node

                if (
                    targetNode in
                    closedNodes
                ) {
                    continue
                }

                val movementCost =
                    movementCost(
                        currentNode,
                        targetNode
                    )

                val nextPathCost =
                    currentPathCost +
                            movementCost

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
                    continue
                }

                pathCosts[
                    targetNode
                ] = nextPathCost

                previousNodes[
                    targetNode
                ] = currentNode

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

        return null
    }

    private fun jump(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        source: NavigationNode,
        directionX: Int,
        directionZ: Int
    ): SimulatedJumpPoint? {
        var currentX =
            source.x

        var currentZ =
            source.z

        while (true) {
            val nextX =
                currentX +
                        directionX

            val nextZ =
                currentZ +
                        directionZ

            if (
                !canMove(
                    map = map,
                    profile =
                        request.traversalProfile,
                    floorHeightUnits =
                        source.floorHeightUnits,
                    fromX =
                        currentX,
                    fromZ =
                        currentZ,
                    toX =
                        nextX,
                    toZ =
                        nextZ
                )
            ) {
                return null
            }

            currentX = nextX
            currentZ = nextZ

            val currentNode =
                NavigationNode(
                    x =
                        currentX,

                    z =
                        currentZ,

                    floorHeightUnits =
                        source.floorHeightUnits
                )

            if (
                currentNode ==
                request.target
            ) {
                return SimulatedJumpPoint(
                    node =
                        currentNode,

                    directionX =
                        directionX,

                    directionZ =
                        directionZ
                )
            }

            if (
                hasForcedNeighbor(
                    map = map,
                    profile =
                        request.traversalProfile,
                    floorHeightUnits =
                        source.floorHeightUnits,
                    x =
                        currentX,
                    z =
                        currentZ,
                    directionX =
                        directionX,
                    directionZ =
                        directionZ
                )
            ) {
                return SimulatedJumpPoint(
                    node =
                        currentNode,

                    directionX =
                        directionX,

                    directionZ =
                        directionZ
                )
            }

            if (
                directionX != 0 &&
                directionZ != 0
            ) {
                if (
                    hasStraightJumpPoint(
                        map = map,
                        request = request,
                        sourceX =
                            currentX,
                        sourceZ =
                            currentZ,
                        directionX =
                            directionX,
                        directionZ =
                            0
                    ) ||
                    hasStraightJumpPoint(
                        map = map,
                        request = request,
                        sourceX =
                            currentX,
                        sourceZ =
                            currentZ,
                        directionX =
                            0,
                        directionZ =
                            directionZ
                    )
                ) {
                    return SimulatedJumpPoint(
                        node =
                            currentNode,

                        directionX =
                            directionX,

                        directionZ =
                            directionZ
                    )
                }
            }
        }
    }

    private fun hasStraightJumpPoint(
        map: SimulatedMap,
        request: SimulatedPathRequest,
        sourceX: Int,
        sourceZ: Int,
        directionX: Int,
        directionZ: Int
    ): Boolean {
        var currentX =
            sourceX

        var currentZ =
            sourceZ

        while (true) {
            val nextX =
                currentX +
                        directionX

            val nextZ =
                currentZ +
                        directionZ

            if (
                !canMove(
                    map = map,
                    profile =
                        request.traversalProfile,
                    floorHeightUnits =
                        request.start
                            .floorHeightUnits,
                    fromX =
                        currentX,
                    fromZ =
                        currentZ,
                    toX =
                        nextX,
                    toZ =
                        nextZ
                )
            ) {
                return false
            }

            currentX = nextX
            currentZ = nextZ

            if (
                currentX ==
                request.target.x &&
                currentZ ==
                request.target.z
            ) {
                return true
            }

            if (
                hasForcedNeighbor(
                    map = map,
                    profile =
                        request.traversalProfile,
                    floorHeightUnits =
                        request.start
                            .floorHeightUnits,
                    x =
                        currentX,
                    z =
                        currentZ,
                    directionX =
                        directionX,
                    directionZ =
                        directionZ
                )
            ) {
                return true
            }
        }
    }

    private fun hasForcedNeighbor(
        map: SimulatedMap,
        profile: SimulatedTraversalProfile,
        floorHeightUnits: Int,
        x: Int,
        z: Int,
        directionX: Int,
        directionZ: Int
    ): Boolean {
        if (
            directionX != 0 &&
            directionZ == 0
        ) {
            if (
                !isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x,
                    z + 1
                ) &&
                isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x + directionX,
                    z + 1
                )
            ) {
                return true
            }

            if (
                !isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x,
                    z - 1
                ) &&
                isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x + directionX,
                    z - 1
                )
            ) {
                return true
            }

            return false
        }

        if (
            directionX == 0 &&
            directionZ != 0
        ) {
            if (
                !isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x + 1,
                    z
                ) &&
                isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x + 1,
                    z + directionZ
                )
            ) {
                return true
            }

            if (
                !isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x - 1,
                    z
                ) &&
                isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x - 1,
                    z + directionZ
                )
            ) {
                return true
            }

            return false
        }

        if (
            directionX != 0
        ) {
            if (
                !isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x - directionX,
                    z
                ) &&
                isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x - directionX,
                    z + directionZ
                )
            ) {
                return true
            }

            if (
                !isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x,
                    z - directionZ
                ) &&
                isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    x + directionX,
                    z - directionZ
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun canMove(
        map: SimulatedMap,
        profile: SimulatedTraversalProfile,
        floorHeightUnits: Int,
        fromX: Int,
        fromZ: Int,
        toX: Int,
        toZ: Int
    ): Boolean {
        if (
            !isWalkable(
                map,
                profile,
                floorHeightUnits,
                toX,
                toZ
            )
        ) {
            return false
        }

        val differenceX =
            toX -
                    fromX

        val differenceZ =
            toZ -
                    fromZ

        if (
            differenceX != 0 &&
            differenceZ != 0
        ) {
            if (
                !isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    fromX +
                            differenceX,
                    fromZ
                ) ||
                !isWalkable(
                    map,
                    profile,
                    floorHeightUnits,
                    fromX,
                    fromZ +
                            differenceZ
                )
            ) {
                return false
            }
        }

        return true
    }

    private fun isWalkable(
        map: SimulatedMap,
        profile: SimulatedTraversalProfile,
        floorHeightUnits: Int,
        x: Int,
        z: Int
    ): Boolean {
        val surface =
            findSurface(
                map,
                x,
                z,
                floorHeightUnits
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

        return !map.hasCollision(
            SimulatedAABB.fromBottomCenter(
                position =
                    NavigationNode(
                        x =
                            x,

                        z =
                            z,

                        floorHeightUnits =
                            floorHeightUnits
                    ).toPosition(),

                width =
                    profile.width,

                height =
                    profile.height
            )
        )
    }

    private fun findSurface(
        map: SimulatedMap,
        x: Int,
        z: Int,
        floorHeightUnits: Int
    ): WalkSurface? {
        val surfaceCount =
            map.surfaceCountAt(
                x,
                z
            )

        var surfaceIndex = 0

        while (
            surfaceIndex <
            surfaceCount
        ) {
            val surface =
                map.surfaceAt(
                    x,
                    z,
                    surfaceIndex
                )

            if (
                surface != null &&
                surface.floorHeightUnits ==
                floorHeightUnits
            ) {
                return surface
            }

            surfaceIndex++
        }

        return null
    }

    private fun canUseJumpPointSearch(
        map: SimulatedMap,
        request: SimulatedPathRequest
    ): Boolean {
        if (
            request.start.floorHeightUnits !=
            request.target.floorHeightUnits
        ) {
            return false
        }

        return isWalkable(
            map,
            request.traversalProfile,
            request.start.floorHeightUnits,
            request.start.x,
            request.start.z
        ) &&
                isWalkable(
                    map,
                    request.traversalProfile,
                    request.target.floorHeightUnits,
                    request.target.x,
                    request.target.z
                )
    }

    private fun movementCost(
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

    private fun heuristic(
        source: NavigationNode,
        target: NavigationNode
    ): Double =
        movementCost(
            source,
            target
        )

    private fun createSuccess(
        request: SimulatedPathRequest,
        previousNodes: Map<
                NavigationNode,
                NavigationNode
                >,
        totalCost: Double
    ): SimulatedPathResult.Success {
        val reversedNodes =
            ArrayList<NavigationNode>()

        var currentNode =
            request.target

        reversedNodes.add(
            currentNode
        )

        while (
            currentNode !=
            request.start
        ) {
            currentNode =
                previousNodes[
                    currentNode
                ] ?: return SimulatedPathResult.Success(
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

            reversedNodes.add(
                currentNode
            )
        }

        reversedNodes.reverse()

        return SimulatedPathResult.Success(
            requestId =
                request.requestId,

            entityId =
                request.entityId,

            mapRevision =
                request.mapRevision,

            path =
                SimulatedPath(
                    reversedNodes
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

    private data class NodeCandidate(
        val node: NavigationNode,
        val pathCost: Double,
        val heuristicCost: Double
    ) {
        val totalEstimatedCost: Double
            get() =
                pathCost +
                        heuristicCost
    }

    private data class Direction(
        val x: Int,
        val z: Int
    )

    companion object {
        private const val
                DEFAULT_MAXIMUM_VISITED_JUMP_POINTS =
            100_000

        private val DIRECTIONS =
            arrayOf(
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
}