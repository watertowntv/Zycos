@file:Suppress("unused")

package zaqws.zycos.simulated.navigation.hpa

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import zaqws.zycos.simulated.map.SimulatedMap
import zaqws.zycos.simulated.paper.map.SimulatedMapRevision
import zaqws.zycos.simulated.navigation.NavigationNode
import zaqws.zycos.simulated.navigation.SimulatedTraversalProfile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class SimulatedHpaGraph private constructor(
    val mapRevision: SimulatedMapRevision,
    val traversalProfile: SimulatedTraversalProfile,
    val clusters:
    Long2ObjectOpenHashMap<SimulatedHpaCluster>,
    private val portals:
    Int2ObjectOpenHashMap<SimulatedHpaPortal>
) {
    val clusterCount: Int
        get() =
            clusters.size

    val portalCount: Int
        get() =
            portals.size

    fun cluster(
        chunkX: Int,
        chunkZ: Int
    ): SimulatedHpaCluster? =
        clusters[
            clusterId(
                chunkX,
                chunkZ
            )
        ]

    fun cluster(
        clusterId: Long
    ): SimulatedHpaCluster? =
        clusters[
            clusterId
        ]

    fun portal(
        portalId: Int
    ): SimulatedHpaPortal? =
        portals[
            portalId
        ]

    fun clusterFor(
        node: NavigationNode
    ): SimulatedHpaCluster? =
        cluster(
            node.chunkX,
            node.chunkZ
        )

    inline fun forEachCluster(
        action: (
            SimulatedHpaCluster
        ) -> Unit
    ) {
        val iterator =
            clusters.values.iterator()

        while (iterator.hasNext()) {
            action(
                iterator.next()
            )
        }
    }

    private inline fun forEachPortal(
        action: (
            SimulatedHpaPortal
        ) -> Unit
    ) {
        val iterator =
            portals.values.iterator()

        while (iterator.hasNext()) {
            action(
                iterator.next()
            )
        }
    }

    companion object {
        fun build(
            map: SimulatedMap,
            traversalProfile:
            SimulatedTraversalProfile
        ): SimulatedHpaGraph {
            val clusterPortals =
                Long2ObjectOpenHashMap<
                        MutableList<
                                SimulatedHpaPortal
                                >
                        >()

            val portals =
                Int2ObjectOpenHashMap<
                        SimulatedHpaPortal
                        >()

            val nextPortalId =
                AtomicInteger(1)

            var chunkZ =
                map.bounds.minimumChunkZ

            while (
                chunkZ <=
                map.bounds.maximumChunkZ
            ) {
                var chunkX =
                    map.bounds.minimumChunkX

                while (
                    chunkX <=
                    map.bounds.maximumChunkX
                ) {
                    if (
                        map.walkSurfaceChunk(
                            chunkX,
                            chunkZ
                        ) != null
                    ) {
                        createHorizontalBoundaryPortals(
                            map = map,
                            traversalProfile =
                                traversalProfile,
                            firstChunkX =
                                chunkX,
                            firstChunkZ =
                                chunkZ,
                            secondChunkX =
                                chunkX + 1,
                            secondChunkZ =
                                chunkZ,
                            nextPortalId =
                                nextPortalId,
                            clusterPortals =
                                clusterPortals,
                            portals =
                                portals
                        )

                        createVerticalBoundaryPortals(
                            map = map,
                            traversalProfile =
                                traversalProfile,
                            firstChunkX =
                                chunkX,
                            firstChunkZ =
                                chunkZ,
                            secondChunkX =
                                chunkX,
                            secondChunkZ =
                                chunkZ + 1,
                            nextPortalId =
                                nextPortalId,
                            clusterPortals =
                                clusterPortals,
                            portals =
                                portals
                        )
                    }

                    chunkX++
                }

                chunkZ++
            }

            val clusters =
                Long2ObjectOpenHashMap<
                        SimulatedHpaCluster
                        >()

            chunkZ =
                map.bounds.minimumChunkZ

            while (
                chunkZ <=
                map.bounds.maximumChunkZ
            ) {
                var chunkX =
                    map.bounds.minimumChunkX

                while (
                    chunkX <=
                    map.bounds.maximumChunkX
                ) {
                    val chunkRevision =
                        map.chunkRevision(
                            chunkX,
                            chunkZ
                        )

                    if (
                        chunkRevision != null
                    ) {
                        val clusterId =
                            clusterId(
                                chunkX,
                                chunkZ
                            )

                        clusters.put(
                            clusterId,
                            SimulatedHpaCluster(
                                clusterId =
                                    clusterId,

                                chunkX =
                                    chunkX,

                                chunkZ =
                                    chunkZ,

                                revision =
                                    chunkRevision,

                                portals =
                                    clusterPortals[
                                        clusterId
                                    ] ?: emptyList()
                            )
                        )
                    }

                    chunkX++
                }

                chunkZ++
            }

            return SimulatedHpaGraph(
                mapRevision =
                    map.revision,

                traversalProfile =
                    traversalProfile,

                clusters =
                    clusters,

                portals =
                    portals
            )
        }

        internal fun clusterId(
            chunkX: Int,
            chunkZ: Int
        ): Long =
            (chunkX.toLong() shl Int.SIZE_BITS) or
                    (
                            chunkZ.toLong() and
                                    0xFFFF_FFFFL
                            )

        private fun createHorizontalBoundaryPortals(
            map: SimulatedMap,
            traversalProfile:
            SimulatedTraversalProfile,
            firstChunkX: Int,
            firstChunkZ: Int,
            secondChunkX: Int,
            secondChunkZ: Int,
            nextPortalId: AtomicInteger,
            clusterPortals:
            Long2ObjectOpenHashMap<
                    MutableList<
                            SimulatedHpaPortal
                            >
                    >,
            portals:
            Int2ObjectOpenHashMap<
                    SimulatedHpaPortal
                    >
        ) {
            if (
                !map.bounds.containsChunk(
                    secondChunkX,
                    secondChunkZ
                )
            ) {
                return
            }

            if (
                map.walkSurfaceChunk(
                    secondChunkX,
                    secondChunkZ
                ) == null
            ) {
                return
            }

            val firstWorldX =
                firstChunkX *
                        CHUNK_SIZE +
                        CHUNK_SIZE -
                        1

            val secondWorldX =
                secondChunkX *
                        CHUNK_SIZE

            val minimumWorldZ =
                maxOf(
                    map.bounds.minimumZ,
                    firstChunkZ *
                            CHUNK_SIZE
                )

            val maximumWorldZ =
                minOf(
                    map.bounds.maximumZ,
                    firstChunkZ *
                            CHUNK_SIZE +
                            CHUNK_SIZE -
                            1
                )

            var worldZ =
                minimumWorldZ

            while (
                worldZ <=
                maximumWorldZ
            ) {
                createPortalsBetweenColumns(
                    map = map,
                    traversalProfile =
                        traversalProfile,
                    firstX =
                        firstWorldX,
                    firstZ =
                        worldZ,
                    secondX =
                        secondWorldX,
                    secondZ =
                        worldZ,
                    firstChunkX =
                        firstChunkX,
                    firstChunkZ =
                        firstChunkZ,
                    secondChunkX =
                        secondChunkX,
                    secondChunkZ =
                        secondChunkZ,
                    nextPortalId =
                        nextPortalId,
                    clusterPortals =
                        clusterPortals,
                    portals =
                        portals
                )

                worldZ++
            }
        }

        private fun createVerticalBoundaryPortals(
            map: SimulatedMap,
            traversalProfile:
            SimulatedTraversalProfile,
            firstChunkX: Int,
            firstChunkZ: Int,
            secondChunkX: Int,
            secondChunkZ: Int,
            nextPortalId: AtomicInteger,
            clusterPortals:
            Long2ObjectOpenHashMap<
                    MutableList<
                            SimulatedHpaPortal
                            >
                    >,
            portals:
            Int2ObjectOpenHashMap<
                    SimulatedHpaPortal
                    >
        ) {
            if (
                !map.bounds.containsChunk(
                    secondChunkX,
                    secondChunkZ
                )
            ) {
                return
            }

            if (
                map.walkSurfaceChunk(
                    secondChunkX,
                    secondChunkZ
                ) == null
            ) {
                return
            }

            val firstWorldZ =
                firstChunkZ *
                        CHUNK_SIZE +
                        CHUNK_SIZE -
                        1

            val secondWorldZ =
                secondChunkZ *
                        CHUNK_SIZE

            val minimumWorldX =
                maxOf(
                    map.bounds.minimumX,
                    firstChunkX *
                            CHUNK_SIZE
                )

            val maximumWorldX =
                minOf(
                    map.bounds.maximumX,
                    firstChunkX *
                            CHUNK_SIZE +
                            CHUNK_SIZE -
                            1
                )

            var worldX =
                minimumWorldX

            while (
                worldX <=
                maximumWorldX
            ) {
                createPortalsBetweenColumns(
                    map = map,
                    traversalProfile =
                        traversalProfile,
                    firstX =
                        worldX,
                    firstZ =
                        firstWorldZ,
                    secondX =
                        worldX,
                    secondZ =
                        secondWorldZ,
                    firstChunkX =
                        firstChunkX,
                    firstChunkZ =
                        firstChunkZ,
                    secondChunkX =
                        secondChunkX,
                    secondChunkZ =
                        secondChunkZ,
                    nextPortalId =
                        nextPortalId,
                    clusterPortals =
                        clusterPortals,
                    portals =
                        portals
                )

                worldX++
            }
        }

        private fun createPortalsBetweenColumns(
            map: SimulatedMap,
            traversalProfile:
            SimulatedTraversalProfile,
            firstX: Int,
            firstZ: Int,
            secondX: Int,
            secondZ: Int,
            firstChunkX: Int,
            firstChunkZ: Int,
            secondChunkX: Int,
            secondChunkZ: Int,
            nextPortalId: AtomicInteger,
            clusterPortals:
            Long2ObjectOpenHashMap<
                    MutableList<
                            SimulatedHpaPortal
                            >
                    >,
            portals:
            Int2ObjectOpenHashMap<
                    SimulatedHpaPortal
                    >
        ) {
            val firstSurfaceCount =
                map.surfaceCountAt(
                    firstX,
                    firstZ
                )

            val secondSurfaceCount =
                map.surfaceCountAt(
                    secondX,
                    secondZ
                )

            if (
                firstSurfaceCount == 0 ||
                secondSurfaceCount == 0
            ) {
                return
            }

            var firstSurfaceIndex = 0

            while (
                firstSurfaceIndex <
                firstSurfaceCount
            ) {
                val firstSurface =
                    map.surfaceAt(
                        firstX,
                        firstZ,
                        firstSurfaceIndex
                    )

                firstSurfaceIndex++

                if (
                    firstSurface == null ||
                    firstSurface.clearanceUnits <
                    traversalProfile.heightUnits ||
                    firstSurface.waterDepthUnits >
                    map.config.maximumWalkableWaterDepthUnits
                ) {
                    continue
                }

                var secondSurfaceIndex = 0

                while (
                    secondSurfaceIndex <
                    secondSurfaceCount
                ) {
                    val secondSurface =
                        map.surfaceAt(
                            secondX,
                            secondZ,
                            secondSurfaceIndex
                        )

                    secondSurfaceIndex++

                    if (
                        secondSurface == null ||
                        secondSurface.clearanceUnits <
                        traversalProfile.heightUnits ||
                        secondSurface.waterDepthUnits >
                        map.config.maximumWalkableWaterDepthUnits
                    ) {
                        continue
                    }

                    val heightDifference =
                        abs(
                            firstSurface.floorHeightUnits -
                                    secondSurface.floorHeightUnits
                        )

                    if (
                        heightDifference >
                        traversalProfile.maximumStepHeightUnits
                    ) {
                        continue
                    }

                    val firstNode =
                        NavigationNode(
                            x =
                                firstX,

                            z =
                                firstZ,

                            floorHeightUnits =
                                firstSurface.floorHeightUnits
                        )

                    val secondNode =
                        NavigationNode(
                            x =
                                secondX,

                            z =
                                secondZ,

                            floorHeightUnits =
                                secondSurface.floorHeightUnits
                        )

                    if (
                        !canOccupy(
                            map,
                            firstNode,
                            traversalProfile
                        ) ||
                        !canOccupy(
                            map,
                            secondNode,
                            traversalProfile
                        )
                    ) {
                        continue
                    }

                    addPortal(
                        firstChunkX =
                            firstChunkX,
                        firstChunkZ =
                            firstChunkZ,
                        secondChunkX =
                            secondChunkX,
                        secondChunkZ =
                            secondChunkZ,
                        firstNode =
                            firstNode,
                        secondNode =
                            secondNode,
                        nextPortalId =
                            nextPortalId,
                        clusterPortals =
                            clusterPortals,
                        portals =
                            portals
                    )
                }
            }
        }

        private fun addPortal(
            firstChunkX: Int,
            firstChunkZ: Int,
            secondChunkX: Int,
            secondChunkZ: Int,
            firstNode: NavigationNode,
            secondNode: NavigationNode,
            nextPortalId: AtomicInteger,
            clusterPortals:
            Long2ObjectOpenHashMap<
                    MutableList<
                            SimulatedHpaPortal
                            >
                    >,
            portals:
            Int2ObjectOpenHashMap<
                    SimulatedHpaPortal
                    >
        ) {
            val portalId =
                nextPortalId
                    .getAndIncrement()

            check(portalId > 0) {
                "HPA portal identifier space exhausted"
            }

            val firstClusterId =
                clusterId(
                    firstChunkX,
                    firstChunkZ
                )

            val secondClusterId =
                clusterId(
                    secondChunkX,
                    secondChunkZ
                )

            val portal =
                SimulatedHpaPortal(
                    portalId =
                        portalId,

                    firstClusterId =
                        firstClusterId,

                    secondClusterId =
                        secondClusterId,

                    firstNode =
                        firstNode,

                    secondNode =
                        secondNode
                )

            portals.put(
                portalId,
                portal
            )

            clusterPortals
                .computeIfAbsent(
                    firstClusterId
                ) {
                    ArrayList()
                }
                .add(
                    portal
                )

            clusterPortals
                .computeIfAbsent(
                    secondClusterId
                ) {
                    ArrayList()
                }
                .add(
                    portal
                )
        }

        private fun canOccupy(
            map: SimulatedMap,
            node: NavigationNode,
            traversalProfile:
            SimulatedTraversalProfile
        ): Boolean =
            !map.hasCollision(
                zaqws.zycos.simulated.math.SimulatedAABB
                    .fromBottomCenter(
                        position =
                            node.toPosition(),

                        width =
                            traversalProfile.width,

                        height =
                            traversalProfile.height
                    )
            )

        private const val CHUNK_SIZE =
            16
    }
}