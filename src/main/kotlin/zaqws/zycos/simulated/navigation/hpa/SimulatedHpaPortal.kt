@file:Suppress("unused")

package zaqws.zycos.simulated.navigation.hpa

import zaqws.zycos.simulated.navigation.NavigationNode

data class SimulatedHpaPortal(
    val portalId: Int,
    val firstClusterId: Long,
    val secondClusterId: Long,
    val firstNode: NavigationNode,
    val secondNode: NavigationNode
) {
    init {
        require(portalId > 0)
        require(firstClusterId != secondClusterId)

        require(
            firstNode.chunkX != secondNode.chunkX ||
                    firstNode.chunkZ != secondNode.chunkZ
        )
    }

    fun connects(
        clusterId: Long
    ): Boolean =
        clusterId == firstClusterId ||
                clusterId == secondClusterId

    fun otherCluster(
        clusterId: Long
    ): Long =
        when (clusterId) {
            firstClusterId ->
                secondClusterId

            secondClusterId ->
                firstClusterId

            else ->
                throw IllegalArgumentException(
                    "Cluster $clusterId is not connected to portal $portalId"
                )
        }

    fun nodeFor(
        clusterId: Long
    ): NavigationNode =
        when (clusterId) {
            firstClusterId ->
                firstNode

            secondClusterId ->
                secondNode

            else ->
                throw IllegalArgumentException(
                    "Cluster $clusterId is not connected to portal $portalId"
                )
        }

    fun otherNode(
        clusterId: Long
    ): NavigationNode =
        when (clusterId) {
            firstClusterId ->
                secondNode

            secondClusterId ->
                firstNode

            else ->
                throw IllegalArgumentException(
                    "Cluster $clusterId is not connected to portal $portalId"
                )
        }
}