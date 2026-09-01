package zaqws.zycos.simulated.navigation

import zaqws.zycos.simulated.entity.SimulatedEntityId
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.paper.map.SimulatedMapRevision

fun interface SimulatedLocalPathfinder {
    fun findPath(map: SimulatedMap, request: SimulatedPathRequest): SimulatedPathResult
}

data class SimulatedPathRequest(
    val requestId: Long,
    val entityId: SimulatedEntityId,
    val start: NavigationNode,
    val target: NavigationNode,
    val traversalProfile: SimulatedTraversalProfile,
    val maximumDropHeightUnits: Int,
    val mapRevision: SimulatedMapRevision
) {
    init {
        require(requestId > 0L)
        require(maximumDropHeightUnits >= 0)
    }
}

sealed interface SimulatedPathResult {
    val requestId: Long
    val entityId: SimulatedEntityId
    val mapRevision: SimulatedMapRevision

    data class Success(
        override val requestId: Long,
        override val entityId: SimulatedEntityId,
        override val mapRevision: SimulatedMapRevision,
        val path: SimulatedPath,
        val totalCost: Double
    ) : SimulatedPathResult {
        init {
            require(totalCost.isFinite() && totalCost >= 0.0)
        }
    }

    data class Unreachable(
        override val requestId: Long,
        override val entityId: SimulatedEntityId,
        override val mapRevision: SimulatedMapRevision
    ) : SimulatedPathResult

    data class Invalid(
        override val requestId: Long,
        override val entityId: SimulatedEntityId,
        override val mapRevision: SimulatedMapRevision
    ) : SimulatedPathResult
}

class SimulatedPath(
    nodes: List<NavigationNode>
) {
    private val nodes =
        nodes.toList()

    init {
        require(this.nodes.isNotEmpty())
    }

    val size: Int
        get() = nodes.size

    val start: NavigationNode
        get() = nodes.first()

    val target: NavigationNode
        get() = nodes.last()

    operator fun get(
        index: Int
    ): NavigationNode =
        nodes[index]

    fun asList(): List<NavigationNode> =
        nodes

    fun subPath(
        startIndex: Int
    ): SimulatedPath {
        require(startIndex in nodes.indices)

        return SimulatedPath(
            nodes.subList(
                startIndex,
                nodes.size
            )
        )
    }

    inline fun forEach(
        action: (NavigationNode) -> Unit
    ) {
        var index = 0

        while (index < size) {
            action(this[index])
            index++
        }
    }

    override fun equals(
        other: Any?
    ): Boolean =
        other is SimulatedPath &&
                nodes == other.nodes

    override fun hashCode(): Int =
        nodes.hashCode()

    override fun toString(): String =
        "SimulatedPath(size=$size, start=$start, target=$target)"
}
