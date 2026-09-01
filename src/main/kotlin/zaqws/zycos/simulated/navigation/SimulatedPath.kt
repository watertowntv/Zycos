@file:Suppress("unused")

package zaqws.zycos.simulated.navigation

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