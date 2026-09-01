@file:Suppress("unused")

package zaqws.zycos.simulated.navigation.hpa

import zaqws.zycos.simulated.paper.map.SimulatedMapRevision
import zaqws.zycos.simulated.navigation.NavigationNode

class SimulatedHpaCluster internal constructor(
    val clusterId: Long,
    val chunkX: Int,
    val chunkZ: Int,
    val revision: SimulatedMapRevision,
    portals: List<SimulatedHpaPortal>
) {
    private val portals =
        portals.toList()

    val portalCount: Int
        get() =
            portals.size

    val isEmpty: Boolean
        get() =
            portals.isEmpty()

    fun portalAt(
        index: Int
    ): SimulatedHpaPortal =
        portals[index]

    fun portals():
            List<SimulatedHpaPortal> =
        portals

    fun contains(
        node: NavigationNode
    ): Boolean =
        node.chunkX == chunkX &&
                node.chunkZ == chunkZ

    fun hasPortal(
        portalId: Int
    ): Boolean {
        for ((portalId1) in portals) {
            if (
                portalId1 ==
                portalId
            ) {
                return true
            }
        }

        return false
    }

    override fun equals(
        other: Any?
    ): Boolean =
        other is SimulatedHpaCluster &&
                clusterId ==
                other.clusterId &&
                revision ==
                other.revision

    override fun hashCode(): Int {
        var result =
            clusterId.hashCode()

        result =
            31 * result +
                    revision.hashCode()

        return result
    }

    override fun toString(): String =
        "SimulatedHpaCluster(" +
                "clusterId=$clusterId, " +
                "chunkX=$chunkX, " +
                "chunkZ=$chunkZ, " +
                "revision=$revision, " +
                "portalCount=$portalCount)"
}
